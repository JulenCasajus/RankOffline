package org.rankoffline.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A single installed pack or the combined RankOfflineAll view. */
sealed interface ViewingScope {
    data class Pack(val packId: PackId) : ViewingScope
    data object All : ViewingScope
    companion object {
        /** Reserved virtual numeric ID; never a physical .pack, directory or catalog PackId. */
        const val ALL_ID = 0L
        fun fromStored(value: Long?): ViewingScope =
            if (value == null || value == ALL_ID) All
            else runCatching { Pack(PackId(value)) }.getOrDefault(All)
    }
    fun storedId(): Long = when (this) {
        All -> ALL_ID
        is Pack -> packId.value
    }
}

/** Combined views contain only explicitly selected packs; Merge changes presentation only. */
internal data class AllCatalogRequest(
    val packIds: List<PackId>,
    val state: SearchUiState,
    val mergeByPack: Map<PackId, Boolean>,
    val revisionToken: Long
)

internal data class AllRankingRequest(
    val packIds: List<PackId>,
    val state: RankingUiState,
    val mergeByPack: Map<PackId, Boolean>,
    val revisionToken: Long
)

internal data class CatalogRequest(
    val packId: PackId,
    val query: String,
    val sort: CatalogSortOrder,
    val filters: CatalogFilters,
    /** Only the list representation; never changes the pack's group relationships. */
    val merge: Boolean,
    val catalogRevision: Long,
    val ratingRevision: Long
)

internal data class RankingRequest(
    val packId: PackId,
    val filters: CatalogFilters,
    val sort: RankingSortOrder,
    val descending: Boolean,
    val ratingCategoryId: String?,
    val merge: Boolean,
    val catalogRevision: Long,
    val ratingRevision: Long
)

/** A page may belong to a pack or RankOfflineAll; null means no request yet. */
data class SearchPageState(
    val scope: ViewingScope? = null,
    val items: List<MediaListEntry> = emptyList(),
    val loadingInitial: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val failed: Boolean = false,
    val query: String = ""
) {
    init { validatePageScope(scope, items) }
}

data class RankingResultState(
    val scope: ViewingScope? = null,
    val items: List<MediaListEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false
) {
    init { validatePageScope(scope, items) }
}

private fun validatePageScope(scope: ViewingScope?, entries: List<MediaListEntry>) {
    when (scope) {
        is ViewingScope.Pack -> require(entries.all { it.packId == scope.packId }) {
            "Results for ${scope.packId.value} contain entries from another pack"
        }
        null -> require(entries.isEmpty()) { "Results must declare a viewing scope" }
        ViewingScope.All -> Unit
    }
}

/** Search alias titles are transient and must not leak into a different query. */
private fun MediaListEntry.withCanonicalSearchTitle(): MediaListEntry = when (this) {
    is MediaListEntry.Item -> if (value.searchDisplayTitle == null) this else
        copy(value = value.copy(searchDisplayTitle = null))
    is MediaListEntry.Group -> this
}

internal fun SearchPageState.visibleItemsFor(
    activeScope: ViewingScope,
    overrides: Map<CatalogEntryKey, RatingDisplayOverride> = emptyMap(),
    activeQuery: String? = null
): List<MediaListEntry> {
    if (scope != activeScope || loadingInitial) return emptyList()
    val visible = if (activeQuery != null && query != activeQuery) {
        items.map(MediaListEntry::withCanonicalSearchTitle)
    } else items
    return if (overrides.isEmpty()) visible else applyRatingOverrides(visible, overrides)
}

internal fun RankingResultState.visibleItemsFor(activeScope: ViewingScope): List<MediaListEntry> =
    if (scope == activeScope && !loading) items else emptyList()

/** Preserve successful results during a refresh, without retaining stale alias titles. */
internal fun retainedSearchPageState(
    previous: SearchPageState,
    scope: ViewingScope,
    query: String
): SearchPageState {
    if (previous.scope != scope || previous.loadingInitial || previous.failed) {
        return SearchPageState(scope = scope, query = query, hasMore = false)
    }
    return previous.copy(
        query = query,
        items = if (previous.query == query) previous.items
            else previous.items.map(MediaListEntry::withCanonicalSearchTitle),
        loadingInitial = false,
        loadingMore = false,
        failed = false
    )
}

internal fun retainedRankingResultState(
    previous: RankingResultState,
    scope: ViewingScope
): RankingResultState =
    if (previous.scope == scope && !previous.loading && !previous.failed) {
        previous.copy(failed = false)
    } else RankingResultState(scope = scope)

internal const val INITIAL_SEARCH_PAGE_SIZE = 200
internal const val NEXT_SEARCH_PAGE_SIZE = 50

internal data class ActiveSearchSnapshot(
    val packId: PackId,
    val state: SearchUiState,
    val merge: Boolean
)

internal data class ActiveRankingSnapshot(
    val packId: PackId,
    val state: RankingUiState,
    val merge: Boolean
)

/** Lazy per-pack revisions; inactive packs and purely visual updates do not invalidate others. */
internal class MediaRevisionTracker {
    private val _catalog = MutableStateFlow<Map<PackId, Long>>(emptyMap())
    val catalog: StateFlow<Map<PackId, Long>> = _catalog.asStateFlow()

    private val _ratings = MutableStateFlow<Map<PackId, Long>>(emptyMap())
    val ratings: StateFlow<Map<PackId, Long>> = _ratings.asStateFlow()

    fun catalogChanged(packId: PackId) = increment(_catalog, packId)

    fun ratingsChanged(packId: PackId) = increment(_ratings, packId)

    private fun increment(flow: MutableStateFlow<Map<PackId, Long>>, packId: PackId) {
        // Compare-and-set prevents lost revisions if independent jobs finish concurrently.
        while (true) {
            val current = flow.value
            val previous = current[packId] ?: 0L
            check(previous < Long.MAX_VALUE) { "Revision overflow for ${packId.value}" }
            if (flow.compareAndSet(current, current + (packId to previous + 1L))) return
        }
    }
}
