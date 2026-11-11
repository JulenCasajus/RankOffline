package org.rankoffline.app

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
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
    internal val session = CatalogSessionCache()
    private var preloadJob: Job? = null
    private var preloadTarget: Pair<PackId, List<PackId>>? = null
    internal val openedTabs = MutableStateFlow<Set<AppTab>>(emptySet())
    internal val filterOptionsState = MutableStateFlow<Map<PackId, Map<String, List<CatalogAttributeValue>>>>(emptyMap())
    val filterOptions: StateFlow<Map<PackId, Map<String, List<CatalogAttributeValue>>>> = filterOptionsState
    /** Called after the first visible page has composed; never downloads or imports packs. */
    fun preloadRecentPacks() {
        val active = (viewingScope.value as? ViewingScope.Pack)?.packId
        if (active == null || packs.states.value.values.any { it.installed && !it.available }) {
            stopPreload()
            return
        }
        val targets = preferences.recentPackIds.value.filter { it != active &&
            packs.states.value[it]?.available == true }.take(if (session.hasAllView) 3 else 4)
        val target = active to targets
        if (preloadJob?.isActive == true && preloadTarget == target) return
        stopPreload()
        preloadTarget = target
        preloadJob = scope.launch(Dispatchers.IO) {
            // Insert from least to most recent; the visible pack remains the MRU entry.
            for (id in targets.asReversed()) {
                try {
                    currentCoroutineContext().ensureActive()
                    if (viewingScope.value != ViewingScope.Pack(active)) return@launch
                    if (packs.states.value.values.any { it.installed && !it.available }) return@launch
                    if (id !in preferences.recentPackIds.value) continue
                    val manifest = packs.states.value[id]?.takeIf { it.available }?.manifest ?: continue
                    preferences.ensureReadyManifestsLoaded(listOf(manifest))
                    val settings = preferences.state.value
                    val merge = settings.mediaSettings[id]?.mergeEnabled == true && manifest.capabilities.merge
                    val catalogRevision = revisions.catalog.value[id] ?: 0L
                    val revision = catalogRevision * 13L + (revisions.ratings.value[id] ?: 0L)
                    val search = searchByPack.value[id] ?: SearchUiState(
                        sortOrder = settings.catalogSortOrders[id] ?: CatalogSortOrder.POPULARITY)
                    val searchKey = CatalogSessionCache.SearchKey(manifest, search, merge, catalogRevision)
                    if (session.findSearch(id, searchKey) == null) {
                        val entries = repository.search(manifest, search, merge, INITIAL_SEARCH_PAGE_SIZE, 0)
                        currentCoroutineContext().ensureActive()
                        if (id !in preferences.recentPackIds.value) continue
                        val accumulator = SearchPageAccumulator<MediaListEntry>()
                            .append(entries, INITIAL_SEARCH_PAGE_SIZE) { it.key }
                        val page = SearchPageState(ViewingScope.Pack(id), accumulator.items,
                            loadingInitial = false, hasMore = accumulator.hasMore, query = search.query,
                            mergedPacks = if (merge) setOf(id) else emptySet(), queryState = search)
                        session.putSearch(id, CatalogSessionCache.SearchSnapshot(searchKey, page, accumulator))
                    }
                    yield()
                    currentCoroutineContext().ensureActive()
                    if (viewingScope.value != ViewingScope.Pack(active)) return@launch
                    if (id !in preferences.recentPackIds.value) continue
                    val validCategories = settings.mediaSettings[id]?.ratingConfig?.categories.orEmpty()
                        .filter { it.active }.mapTo(hashSetOf()) { it.id }
                    val ranking = (rankingByPack.value[id] ?: RankingUiState())
                        .withValidRatingCategory(validCategories)
                    val rankingKey = CatalogSessionCache.RankingKey(manifest, ranking, merge, revision)
                    if (session.findRanking(id, rankingKey) == null) {
                        val entries = repository.ranking(manifest, ranking, merge, INITIAL_SEARCH_PAGE_SIZE, 0)
                        currentCoroutineContext().ensureActive()
                        if (id !in preferences.recentPackIds.value) continue
                        val accumulator = SearchPageAccumulator<MediaListEntry>()
                            .append(entries, INITIAL_SEARCH_PAGE_SIZE) { it.key }
                        val page = RankingPage(RankingResultState(ViewingScope.Pack(id), accumulator.items,
                            loading = false, mergedPacks = if (merge) setOf(id) else emptySet(),
                            queryState = ranking),
                            hasMore = accumulator.hasMore)
                        session.putRanking(id, CatalogSessionCache.RankingSnapshot(rankingKey, page, accumulator))
                    }
                    session.touch(ViewingScope.Pack(active))
                    yield()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    Log.w("RankOfflineCatalog", "Recent pack preload failed: ${id.value}", failure)
                }
            }
        }
    }
    fun stopPreload() {
        preloadJob?.cancel()
        preloadJob = null
        preloadTarget = null
    }
    /** Read the selected pack's valid page synchronously during the frame where scope changes. */
    fun cachedSearchPage(id: PackId): SearchPageState? {
        val manifest = packs.states.value[id]?.takeIf { it.available }?.manifest ?: return null
        val state = searchByPack.value[id] ?: SearchUiState()
        val merge = preferences.state.value.mediaSettings[id]?.mergeEnabled == true && manifest.capabilities.merge
        val revision = revisions.catalog.value[id] ?: 0L
        return session.findSearch(id, CatalogSessionCache.SearchKey(manifest, state, merge, revision))?.page
    }
    fun cachedRankingPage(id: PackId): RankingPage? {
        val manifest = packs.states.value[id]?.takeIf { it.available }?.manifest ?: return null
        val validCategories = preferences.state.value.mediaSettings[id]?.ratingConfig?.categories.orEmpty()
            .filter { it.active }.mapTo(hashSetOf()) { it.id }
        val state = (rankingByPack.value[id] ?: RankingUiState())
            .withValidRatingCategory(validCategories)
        val merge = preferences.state.value.mediaSettings[id]?.mergeEnabled == true && manifest.capabilities.merge
        val revision = (revisions.catalog.value[id] ?: 0L) * 13L + (revisions.ratings.value[id] ?: 0L)
        return session.findRanking(id, CatalogSessionCache.RankingKey(manifest, state, merge, revision))?.page
    }
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
        val catalogRevision: Long,
        val revision: Long
    )

    private val scopedStates = combine(searchByPack, rankingByPack, allSearch, allRanking) {
        searches, rankings, allSearchState, allRankingState ->
        Pair(searches to rankings, allSearchState to allRankingState)
    }
    private val basePlan = combine(
        viewingScope, preferences.state, scopedStates, packs.states
    ) { selection, settings, statesByScope, states ->
        val (byPack, all) = statesByScope
        val (searches, rankings) = byPack
        val (allSearchState, allRankingState) = all
        val search = when (selection) {
            ViewingScope.All -> allSearchState
            is ViewingScope.Pack -> searches[selection.packId] ?: SearchUiState()
        }
        val ranking = when (selection) {
            ViewingScope.All -> allRankingState
            is ViewingScope.Pack -> rankings[selection.packId] ?: RankingUiState()
        }
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
        }, selection == ViewingScope.All && manifests.isEmpty() &&
            states.values.any { it.installed && !it.available }, 0L, 0L)
    }
    private val plan = combine(basePlan, versions) { original, (catalog, ratings) ->
        original.copy(catalogRevision = original.manifests.fold(0L) { token, manifest ->
            token * 31L + (catalog[manifest.packId] ?: 0L)
        }, revision = original.manifests.fold(0L) { token, manifest ->
            val id = manifest.packId
            token * 31L + (catalog[id] ?: 0L) * 13L + (ratings[id] ?: 0L)
        })
    }

    private data class SearchPlan(val plan: Plan, val enabled: Boolean)
    private val searchPlan = combine(plan, openedTabs) { current, opened ->
        SearchPlan(current, AppTab.SEARCH in opened)
    }.distinctUntilChanged { previous, current ->
        previous.enabled == current.enabled &&
            previous.plan.scope == current.plan.scope &&
            previous.plan.manifests == current.plan.manifests &&
            previous.plan.search == current.plan.search &&
            previous.plan.merges == current.plan.merges &&
            previous.plan.blockedByUnreadyPack == current.plan.blockedByUnreadyPack &&
            previous.plan.catalogRevision == current.plan.catalogRevision
    }.debounce(300)

    private val fetchedSearchPage = searchPlan.flatMapLatest { request ->
        if (!request.enabled) return@flatMapLatest emptyFlow<Pair<Plan, SearchPageState>?>()
        val selected = request.plan.scope
        flow<Pair<Plan, SearchPageState>?> {
            val search = request.plan.search
            if (request.plan.blockedByUnreadyPack || request.plan.manifests.isEmpty()) {
                emit(request.plan to SearchPageState(scope = selected, query = search.query,
                    loadingInitial = false, hasMore = false, failed = request.plan.blockedByUnreadyPack,
                    mergedPacks = request.plan.merges.filterValues { it }.keys,
                    queryState = search))
                return@flow
            }
            try {
                if (selected == ViewingScope.All) {
                    preferences.ensureReadyManifestsLoaded(request.plan.manifests)
                    val key = CatalogSessionCache.AllSearchKey(request.plan.manifests, search,
                        request.plan.merges, request.plan.catalogRevision)
                    val cached = session.findAllSearch(key)?.takeIf { !it.page.failed }
                    var cursor = cached?.cursor ?: AllCatalogCursor()
                    val rows = cached?.page?.items?.toMutableList() ?: mutableListOf()
                    if (cached != null) emit(request.plan to cached.page)
                    suspend fun fetch(limit: Int): Boolean {
                        val states = request.plan.manifests.associate { it.packId to search }
                        val page = repository.searchAllPage(request.plan.manifests, states,
                            request.plan.merges, limit, cursor)
                        cursor = page.nextCursor
                        val seen = rows.mapTo(hashSetOf()) { it.key }
                        rows.addAll(page.entries.filter { seen.add(it.key) })
                        val result = SearchPageState(selected, rows.toList(),
                            loadingInitial = false, hasMore = page.hasMore, query = search.query,
                            mergedPacks = request.plan.merges.filterValues { it }.keys,
                            queryState = search)
                        session.putAllSearch(CatalogSessionCache.AllSearchSnapshot(key, result, cursor))
                        emit(request.plan to result)
                        return page.hasMore
                    }
                    var hasMore = cached?.page?.hasMore ?: fetch(INITIAL_SEARCH_PAGE_SIZE)
                    moreSearch.collect { if (hasMore) hasMore = fetch(NEXT_SEARCH_PAGE_SIZE) }
                } else {
                    val manifest = request.plan.manifests.single()
                    val id = manifest.packId
                    val key = CatalogSessionCache.SearchKey(manifest, search,
                        request.plan.merges[id] == true, request.plan.catalogRevision)
                    val cached = session.findSearch(id, key)?.takeIf { !it.page.failed }
                    var accumulator = cached?.accumulator ?: SearchPageAccumulator()
                    if (cached != null) emit(request.plan to cached.page)
                    suspend fun fetch(limit: Int): Boolean {
                        val page = repository.search(manifest, search,
                            key.merge,
                            limit, accumulator.nextOffset)
                        accumulator = accumulator.append(page, limit) { it.key }
                        val result = SearchPageState(selected, accumulator.items, loadingInitial = false,
                            hasMore = accumulator.hasMore, query = search.query,
                            mergedPacks = if (key.merge) setOf(id) else emptySet(),
                            queryState = search)
                        session.putSearch(id, CatalogSessionCache.SearchSnapshot(key, result, accumulator))
                        emit(request.plan to result)
                        return accumulator.hasMore
                    }
                    var hasMore = cached?.page?.hasMore ?: fetch(INITIAL_SEARCH_PAGE_SIZE)
                    moreSearch.collect { if (hasMore) hasMore = fetch(NEXT_SEARCH_PAGE_SIZE) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("RankOfflineCatalog", "Search failed", failure)
                val previous = (selected as? ViewingScope.Pack)?.let { id ->
                    session.search.value[id.packId]?.takeIf {
                        it.key.manifest == request.plan.manifests.singleOrNull() &&
                            it.page.mergedPacks == request.plan.merges.filterValues { merged -> merged }.keys
                    }?.page
                }
                val result = previous?.copy(loadingInitial = false, hasMore = false,
                    failed = true, queryState = search)
                    ?: SearchPageState(scope = selected, loadingInitial = false, hasMore = false,
                        failed = true, query = search.query,
                        mergedPacks = request.plan.merges.filterValues { it }.keys,
                        queryState = search)
                if (selected is ViewingScope.Pack) {
                    val manifest = request.plan.manifests.single()
                    val key = CatalogSessionCache.SearchKey(manifest, search,
                        request.plan.merges[manifest.packId] == true, request.plan.catalogRevision)
                    session.putSearch(manifest.packId, CatalogSessionCache.SearchSnapshot(
                        key, result, SearchPageAccumulator()))
                }
                emit(request.plan to result)
            }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    val searchPageState: StateFlow<SearchPageState> = combine(
        plan, session.search, session.allSearch, fetchedSearchPage
    ) { current, cached, cachedAll, fetched -> when (val selected = current.scope) {
            ViewingScope.All -> {
                val key = CatalogSessionCache.AllSearchKey(current.manifests, current.search,
                    current.merges, current.catalogRevision)
                session.findAllSearch(key)?.page
                    ?: fetched?.takeIf { it.first == current }?.second
                    ?: cachedAll?.takeIf { it.key.manifests == current.manifests &&
                        it.key.merges == current.merges &&
                        it.page.items.isNotEmpty() }?.page?.copy(hasMore = false)
                    ?: SearchPageState(scope = selected, mergedPacks = current.merges.filterValues { it }.keys,
                        queryState = current.search)
            }
            is ViewingScope.Pack -> {
                val manifest = current.manifests.singleOrNull()
                if (manifest == null) SearchPageState(scope = selected, loadingInitial = false,
                    hasMore = false, mergedPacks = emptySet(), queryState = current.search)
                else {
                    val key = CatalogSessionCache.SearchKey(manifest, current.search,
                        current.merges[manifest.packId] == true, current.catalogRevision)
                    session.findSearch(manifest.packId, key)?.page
                        ?: fetched?.takeIf { it.first == current }?.second
                        ?: cached[manifest.packId]?.takeIf { it.key.manifest == manifest &&
                            it.key.merge == (current.merges[manifest.packId] == true) &&
                            it.page.items.isNotEmpty() }?.page?.copy(hasMore = false)
                        ?: SearchPageState(scope = selected, query = current.search.query,
                            mergedPacks = current.merges.filterValues { it }.keys,
                            queryState = current.search)
                }
            }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), SearchPageState())

    data class RankingPage(
        val result: RankingResultState = RankingResultState(),
        val hasMore: Boolean = false
    )

    /** Re-fetch bounded top-N per pack for All: correctly ordered across packs, never unbounded. */
    private val fetchedRankingPage = combine(plan, openedTabs) { current, opened ->
        current.takeIf { AppTab.RANKING in opened }
    }.distinctUntilChanged { previous, current ->
        if (previous == null || current == null) previous == current else
            previous.scope == current.scope &&
                previous.manifests == current.manifests &&
                previous.ranking == current.ranking &&
                previous.merges == current.merges &&
                previous.blockedByUnreadyPack == current.blockedByUnreadyPack &&
                previous.revision == current.revision
    }.flatMapLatest { current ->
        if (current == null) return@flatMapLatest emptyFlow<Pair<Plan, RankingPage>?>()
        flow<Pair<Plan, RankingPage>?> {
            val selected = current.scope
            if (current.blockedByUnreadyPack || current.manifests.isEmpty()) {
                emit(current to RankingPage(RankingResultState(selected, emptyList(), loading = false,
                    failed = current.blockedByUnreadyPack,
                    mergedPacks = current.merges.filterValues { it }.keys,
                    queryState = current.ranking)))
                return@flow
            }
            try {
                if (selected == ViewingScope.All) {
                    preferences.ensureReadyManifestsLoaded(current.manifests)
                }
                if (selected == ViewingScope.All) {
                    val key = CatalogSessionCache.AllRankingKey(current.manifests, current.ranking,
                        current.merges, current.revision)
                    val cached = session.findAllRanking(key)?.takeIf { !it.page.result.failed }
                    var cursor = cached?.cursor ?: AllRankingCursor()
                    val entries = cached?.page?.result?.items?.toMutableList() ?: mutableListOf()
                    if (cached != null) emit(current to cached.page)
                    suspend fun fetch(batchSize: Int): Boolean {
                        val page = repository.rankingAllPage(current.manifests, current.ranking,
                            current.merges, batchSize, cursor)
                        cursor = page.nextCursor
                        val seen = entries.mapTo(hashSetOf()) { it.key }
                        entries.addAll(page.entries.filter { seen.add(it.key) })
                        val result = RankingPage(RankingResultState(selected, entries.toList(),
                            loading = false, mergedPacks = current.merges.filterValues { it }.keys,
                            queryState = current.ranking),
                            hasMore = page.hasMore)
                        session.putAllRanking(CatalogSessionCache.AllRankingSnapshot(key, result, cursor))
                        emit(current to result)
                        return page.hasMore
                    }
                    var hasMore = cached?.page?.hasMore ?: fetch(INITIAL_SEARCH_PAGE_SIZE)
                    moreRanking.collect { if (hasMore) hasMore = fetch(NEXT_SEARCH_PAGE_SIZE) }
                } else {
                    val manifest = current.manifests.single()
                    val id = manifest.packId
                    val key = CatalogSessionCache.RankingKey(manifest, current.ranking,
                        current.merges[id] == true, current.revision)
                    val cached = session.findRanking(id, key)?.takeIf { !it.page.result.failed }
                    var accumulator = cached?.accumulator ?: SearchPageAccumulator()
                    if (cached != null) emit(current to cached.page)
                    suspend fun fetch(batchSize: Int): Boolean {
                        val chunk = repository.ranking(manifest, current.ranking,
                            key.merge, batchSize, accumulator.nextOffset)
                        accumulator = accumulator.append(chunk, batchSize) { it.key }
                        val result = RankingPage(RankingResultState(selected, accumulator.items,
                            loading = false, mergedPacks = if (key.merge) setOf(id) else emptySet(),
                            queryState = current.ranking),
                            hasMore = accumulator.hasMore)
                        session.putRanking(id, CatalogSessionCache.RankingSnapshot(key, result, accumulator))
                        emit(current to result)
                        return accumulator.hasMore
                    }
                    var hasMore = cached?.page?.hasMore ?: fetch(INITIAL_SEARCH_PAGE_SIZE)
                    moreRanking.collect { if (hasMore) hasMore = fetch(NEXT_SEARCH_PAGE_SIZE) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Log.e("RankOfflineCatalog", "Ranking failed", failure)
                val previous = (selected as? ViewingScope.Pack)?.let { id ->
                    session.ranking.value[id.packId]?.takeIf {
                        it.key.manifest == current.manifests.singleOrNull() &&
                            it.page.result.mergedPacks == current.merges.filterValues { merged -> merged }.keys
                    }?.page
                }
                val result = previous?.copy(result = previous.result.copy(loading = false,
                    failed = true, queryState = current.ranking), hasMore = false)
                    ?: RankingPage(RankingResultState(selected, emptyList(), loading = false,
                        failed = true, mergedPacks = current.merges.filterValues { it }.keys,
                        queryState = current.ranking))
                if (selected is ViewingScope.Pack) {
                    val manifest = current.manifests.single()
                    val key = CatalogSessionCache.RankingKey(manifest, current.ranking,
                        current.merges[manifest.packId] == true, current.revision)
                    session.putRanking(manifest.packId, CatalogSessionCache.RankingSnapshot(
                        key, result, SearchPageAccumulator()))
                }
                emit(current to result)
            }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    val rankingPage: StateFlow<RankingPage> = combine(
        plan, session.ranking, session.allRanking, fetchedRankingPage
    ) { current, cached, cachedAll, fetched -> when (val selected = current.scope) {
            ViewingScope.All -> {
                val key = CatalogSessionCache.AllRankingKey(current.manifests, current.ranking,
                    current.merges, current.revision)
                session.findAllRanking(key)?.page
                    ?: fetched?.takeIf { it.first == current }?.second
                    ?: cachedAll?.takeIf { it.key.manifests == current.manifests &&
                        it.key.merges == current.merges &&
                        it.page.result.items.isNotEmpty() }?.page?.copy(hasMore = false)
                    ?: RankingPage()
            }
            is ViewingScope.Pack -> {
                val manifest = current.manifests.singleOrNull()
                if (manifest == null) RankingPage(RankingResultState(selected, loading = false,
                    mergedPacks = emptySet(), queryState = current.ranking))
                else {
                    val key = CatalogSessionCache.RankingKey(manifest, current.ranking,
                        current.merges[manifest.packId] == true, current.revision)
                    session.findRanking(manifest.packId, key)?.page
                        ?: fetched?.takeIf { it.first == current }?.second
                        ?: cached[manifest.packId]?.takeIf { it.key.manifest == manifest &&
                            it.key.merge == (current.merges[manifest.packId] == true) &&
                            it.page.result.items.isNotEmpty() }?.page?.copy(hasMore = false)
                        ?: RankingPage()
                }
            }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), RankingPage())

}
