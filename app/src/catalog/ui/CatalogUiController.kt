package org.rankoffline.app

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** UI-only filter/page orchestration. All actual catalog reads belong to MediaRepository. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
internal class CatalogUiController(
    internal val scope: CoroutineScope,
    internal val repository: MediaRepository,
    internal val packs: PackManager,
    internal val preferences: SettingsRepository,
    internal val viewingScope: StateFlow<ViewingScope>,
    internal val revisions: MediaRevisionTracker
) {
    internal val searchByPack = MutableStateFlow<Map<PackId, SearchUiState>>(emptyMap())
    internal val rankingByPack = MutableStateFlow<Map<PackId, RankingUiState>>(emptyMap())
    internal val allSearch = MutableStateFlow(SearchUiState())
    internal val allRanking = MutableStateFlow(RankingUiState())
    internal val moreSearch = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val moreRanking = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val ratingDisplayOverrides = MutableStateFlow<Map<CatalogEntryKey, RatingDisplayOverride>>(emptyMap())
    internal val openedTabs = MutableStateFlow<Set<AppTab>>(emptySet())
    internal val filterOptionsState = MutableStateFlow<Map<PackId, Map<String, List<CatalogAttributeValue>>>>(emptyMap())
    val filterOptions: StateFlow<Map<PackId, Map<String, List<CatalogAttributeValue>>>> = filterOptionsState

    /** Available only on demand when the user opens filter controls. */
    fun refreshFilterOptions(packId: PackId) = scope.launch {
        val manifest = packs.states.value[packId]?.takeIf { it.available }?.manifest ?: return@launch
        try {
            filterOptionsState.value = filterOptionsState.value +
                (packId to repository.filterOptions(manifest))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            Log.e("RankOfflineCatalog", "Failed loading filter options", failure)
        }
    }


    val activeSearchState: StateFlow<SearchUiState> = combine(
        viewingScope, searchByPack, allSearch
    ) { selected, packs, all -> when (selected) {
        ViewingScope.All -> all
        is ViewingScope.Pack -> packs[selected.packId] ?: SearchUiState()
    } }.stateIn(scope, SharingStarted.Eagerly, SearchUiState())

    val activeRankingState: StateFlow<RankingUiState> = combine(
        viewingScope, rankingByPack, allRanking
    ) { selected, packs, all -> when (selected) {
        ViewingScope.All -> all
        is ViewingScope.Pack -> packs[selected.packId] ?: RankingUiState()
    } }.stateIn(scope, SharingStarted.Eagerly, RankingUiState())

    private val versions = combine(revisions.catalog, revisions.ratings) { catalog, ratings ->
        catalog to ratings
    }

    private data class Plan(
        val scope: ViewingScope,
        val manifests: List<PackManifest>,
        val search: SearchUiState,
        val ranking: RankingUiState,
        val merges: Map<PackId, Boolean>,
        val blockedByUnreadyPack: Boolean,
        val revision: Long
    )

    private val basePlan = combine(
        viewingScope, preferences.state, activeSearchState, activeRankingState, packs.states
    ) { selection, settings, search, ranking, states ->
        val manifests = when (selection) {
            is ViewingScope.Pack -> listOfNotNull(states[selection.packId]
                ?.takeIf { it.available }?.manifest)
            ViewingScope.All -> states.values.filter { it.available }
                .mapNotNull { it.manifest }.sortedBy { it.packId.value }
        }
        val validCategories = when (selection) {
            is ViewingScope.Pack -> settings.mediaSettings[selection.packId]?.ratingConfig
                ?.categories.orEmpty().filter { it.active }.mapTo(hashSetOf()) { it.id }
            ViewingScope.All -> compatibleAllRatingCategories(manifests.map { it.packId },
                settings.mediaSettings).mapTo(hashSetOf()) { it.id }
        }
        Plan(selection, manifests, search, ranking.withValidRatingCategory(validCategories),
            manifests.associate { manifest ->
            manifest.packId to ((if (selection == ViewingScope.All)
                settings.allViewSettings.mergeEnabled
                else settings.mediaSettings[manifest.packId]?.mergeEnabled == true) && manifest.capabilities.merge)
        }, selection == ViewingScope.All && states.values.any { it.installed && !it.available }, 0L)
    }
    private val plan = combine(basePlan, versions) { original, (catalog, ratings) ->
        original.copy(revision = original.manifests.fold(0L) { token, manifest ->
            val id = manifest.packId
            token * 31L + (catalog[id] ?: 0L) * 13L + (ratings[id] ?: 0L)
        })
    }

    private data class SearchPlan(val plan: Plan, val enabled: Boolean)
    private val searchPlan = combine(plan, openedTabs) { current, opened ->
        SearchPlan(current, AppTab.SEARCH in opened)
    }.debounce(300).distinctUntilChanged()

    val searchPageState: StateFlow<SearchPageState> = searchPlan.flatMapLatest { request ->
        if (!request.enabled) return@flatMapLatest emptyFlow<SearchPageState>()
        val selected = request.plan.scope
        flow {
            val search = request.plan.search
            if (request.plan.blockedByUnreadyPack || request.plan.manifests.isEmpty()) {
                emit(SearchPageState(scope = selected, query = search.query, loadingInitial = false,
                    hasMore = false, failed = request.plan.blockedByUnreadyPack))
                return@flow
            }
            try {
                if (selected == ViewingScope.All) {
                    preferences.ensurePackSettingsLoaded(request.plan.manifests.map { it.packId })
                    var cursor = AllCatalogCursor()
                    val rows = mutableListOf<MediaListEntry>()
                    suspend fun fetch(limit: Int): Boolean {
                        val states = request.plan.manifests.associate { it.packId to search }
                        val page = repository.searchAllPage(request.plan.manifests, states,
                            request.plan.merges, limit, cursor)
                        cursor = page.nextCursor
                        val seen = rows.mapTo(hashSetOf()) { it.key }
                        rows.addAll(page.entries.filter { seen.add(it.key) })
                        emit(SearchPageState(selected, rows.toList(), loadingInitial = false,
                            hasMore = page.hasMore, query = search.query))
                        return page.hasMore
                    }
                    var hasMore = fetch(INITIAL_SEARCH_PAGE_SIZE)
                    moreSearch.collect { if (hasMore) hasMore = fetch(NEXT_SEARCH_PAGE_SIZE) }
                } else {
                    val manifest = request.plan.manifests.single()
                    var accumulator = SearchPageAccumulator<MediaListEntry>()
                    suspend fun fetch(limit: Int): Boolean {
                        val page = repository.search(manifest, search,
                            request.plan.merges[manifest.packId] == true,
                            limit, accumulator.nextOffset)
                        accumulator = accumulator.append(page, limit) { it.key }
                        emit(SearchPageState(selected, accumulator.items, loadingInitial = false,
                            hasMore = accumulator.hasMore, query = search.query))
                        return accumulator.hasMore
                    }
                    var hasMore = fetch(INITIAL_SEARCH_PAGE_SIZE)
                    moreSearch.collect { if (hasMore) hasMore = fetch(NEXT_SEARCH_PAGE_SIZE) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("RankOfflineCatalog", "Search failed", failure)
                emit(SearchPageState(scope = selected, loadingInitial = false, hasMore = false,
                    failed = true, query = search.query))
            }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), SearchPageState())

    data class RankingPage(
        val result: RankingResultState = RankingResultState(),
        val hasMore: Boolean = false,
        val loadingMore: Boolean = false
    )

    /** Re-fetch bounded top-N per pack for All: correctly ordered across packs, never unbounded. */
    val rankingPage: StateFlow<RankingPage> = combine(plan, openedTabs) { current, opened ->
        current.takeIf { AppTab.RANKING in opened }
    }.distinctUntilChanged().flatMapLatest { current ->
        if (current == null) return@flatMapLatest emptyFlow<RankingPage>()
        flow {
            val selected = current.scope
            if (current.blockedByUnreadyPack || current.manifests.isEmpty()) {
                emit(RankingPage(RankingResultState(selected, emptyList(), loading = false,
                    failed = current.blockedByUnreadyPack)))
                return@flow
            }
            try {
                if (selected == ViewingScope.All) {
                    preferences.ensurePackSettingsLoaded(current.manifests.map { it.packId })
                }
                if (selected == ViewingScope.All) {
                    var cursor = AllRankingCursor()
                    val entries = mutableListOf<MediaListEntry>()
                    suspend fun fetch(batchSize: Int): Boolean {
                        val page = repository.rankingAllPage(current.manifests, current.ranking,
                            current.merges, batchSize, cursor)
                        cursor = page.nextCursor
                        val seen = entries.mapTo(hashSetOf()) { it.key }
                        entries.addAll(page.entries.filter { seen.add(it.key) })
                        emit(RankingPage(RankingResultState(selected, entries.toList(), loading = false),
                            hasMore = page.hasMore))
                        return page.hasMore
                    }
                    var hasMore = fetch(INITIAL_SEARCH_PAGE_SIZE)
                    moreRanking.collect { if (hasMore) hasMore = fetch(NEXT_SEARCH_PAGE_SIZE) }
                } else {
                    val manifest = current.manifests.single()
                    var accumulator = SearchPageAccumulator<MediaListEntry>()
                    suspend fun fetch(batchSize: Int): Boolean {
                        val chunk = repository.ranking(manifest, current.ranking,
                            current.merges[manifest.packId] == true, batchSize, accumulator.nextOffset)
                        accumulator = accumulator.append(chunk, batchSize) { it.key }
                        emit(RankingPage(RankingResultState(selected, accumulator.items, loading = false),
                            hasMore = accumulator.hasMore))
                        return accumulator.hasMore
                    }
                    var hasMore = fetch(INITIAL_SEARCH_PAGE_SIZE)
                    moreRanking.collect { if (hasMore) hasMore = fetch(NEXT_SEARCH_PAGE_SIZE) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Log.e("RankOfflineCatalog", "Ranking failed", failure)
                emit(RankingPage(RankingResultState(selected, emptyList(), loading = false, failed = true)))
            }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), RankingPage())

    val rankingResultState: StateFlow<RankingResultState> = rankingPage.map { it.result }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), RankingResultState())

    val searchResults: StateFlow<List<MediaListEntry>> = combine(
        viewingScope, searchPageState, ratingDisplayOverrides, activeSearchState
    ) { selected, page, overrides, search -> page.visibleItemsFor(selected, overrides, search.query) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val ranking: StateFlow<List<MediaListEntry>> = combine(
        viewingScope, rankingResultState, ratingDisplayOverrides
    ) { selected, result, overrides -> applyRatingOverrides(result.visibleItemsFor(selected), overrides) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

}
