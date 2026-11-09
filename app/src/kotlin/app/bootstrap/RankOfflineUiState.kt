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

/** A page may belong to a pack or RankOfflineAll; null means no request yet. */
data class SearchPageState(
    val scope: ViewingScope? = null,
    val items: List<MediaListEntry> = emptyList(),
    val loadingInitial: Boolean = true,
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

internal const val INITIAL_SEARCH_PAGE_SIZE = 200
internal const val NEXT_SEARCH_PAGE_SIZE = 50

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
