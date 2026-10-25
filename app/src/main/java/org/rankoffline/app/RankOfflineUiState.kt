package org.rankoffline.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RatingEditorState(
    val anime: Anime,
    val isGroup: Boolean = false,
    val draft: RatingDraft? = null
)

internal data class CatalogRequest(
    val mediaType: MediaType,
    val query: String,
    val sort: CatalogSortOrder,
    val animeFilters: AnimeFilters,
    val genericFilters: GenericMediaFilters,
    val merge: Boolean,
    val catalogRevision: Long,
    val ratingRevision: Long
)

internal fun CatalogRequest.filtersForMedia(): GenericMediaFilters =
    if (mediaType == MediaType.ANIME) animeFilters.toGenericMediaFilters() else genericFilters

internal data class RatingDisplayOverride(
    val finalScore: Double?,
    val qualityScore: Double?,
    val additiveScore: Double?
)

internal fun applyRatingOverrides(
    items: List<Anime>,
    overrides: Map<String, RatingDisplayOverride>
): List<Anime> = items.map { item ->
    overrides[item.id]?.let { override ->
        item.copy(
            finalScore = override.finalScore,
            baseScore = override.qualityScore,
            additiveScore = override.additiveScore
        )
    } ?: item
}

data class SearchPageState(
    val mediaType: MediaType? = null,
    val items: List<Anime> = emptyList(),
    val loadingInitial: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val failed: Boolean = false,
    val query: String = ""
)

data class RankingResultState(
    val mediaType: MediaType? = null,
    val items: List<Anime> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false
)

internal fun SearchPageState.visibleItemsFor(
    activeMediaType: MediaType,
    overrides: Map<String, RatingDisplayOverride> = emptyMap(),
    activeQuery: String? = null
): List<Anime> = if (mediaType == activeMediaType && !loadingInitial) {
    val querySafeItems = if (activeQuery != null && query != activeQuery) {
        items.map { item ->
            item.canonicalTitle?.let { item.copy(title = it, canonicalTitle = null) } ?: item
        }
    } else items
    applyRatingOverrides(querySafeItems, overrides)
} else {
    emptyList()
}

internal fun RankingResultState.visibleItemsFor(activeMediaType: MediaType): List<Anime> =
    if (mediaType == activeMediaType && !loading) items else emptyList()

internal fun retainedSearchPageState(
    previous: SearchPageState,
    mediaType: MediaType,
    query: String
): SearchPageState {
    if (previous.mediaType != mediaType || previous.loadingInitial || previous.failed) {
        return SearchPageState(mediaType = mediaType, query = query, loadingInitial = true, hasMore = false)
    }
    val retainedItems = if (previous.query == query) previous.items else previous.items.map { item ->
        item.canonicalTitle?.let { item.copy(title = it, canonicalTitle = null) } ?: item
    }
    return previous.copy(
        query = query,
        items = retainedItems,
        loadingInitial = false,
        loadingMore = false,
        failed = false
    )
}

internal fun retainedRankingResultState(
    previous: RankingResultState,
    mediaType: MediaType
): RankingResultState = if (previous.mediaType == mediaType && !previous.loading && !previous.failed) {
    previous.copy(failed = false)
} else {
    RankingResultState(mediaType = mediaType, loading = true)
}

internal const val INITIAL_SEARCH_PAGE_SIZE = 200
internal const val NEXT_SEARCH_PAGE_SIZE = 50

internal data class ActiveSearchSnapshot(
    val mediaType: MediaType,
    val state: SearchUiState,
    val merge: Boolean
)

internal data class ActiveRankingSnapshot(
    val mediaType: MediaType,
    val state: RankingUiState,
    val merge: Boolean
)

internal data class RankingRequest(
    val mediaType: MediaType,
    val animeFilters: AnimeFilters,
    val genericFilters: GenericMediaFilters,
    val sort: RankingSortOrder,
    val descending: Boolean,
    val categoryId: String?,
    val merge: Boolean,
    val catalogRevision: Long,
    val ratingRevision: Long
)

internal fun RankingRequest.filtersForMedia(): GenericMediaFilters =
    if (mediaType == MediaType.ANIME) animeFilters.toGenericMediaFilters() else genericFilters

/** Per-media revisions keep inactive categories and visual-only changes from restarting queries. */
internal class MediaRevisionTracker {
    private val _catalog = MutableStateFlow(MediaType.entries.associateWith { 0L })
    val catalog: StateFlow<Map<MediaType, Long>> = _catalog.asStateFlow()

    private val _ratings = MutableStateFlow(MediaType.entries.associateWith { 0L })
    val ratings: StateFlow<Map<MediaType, Long>> = _ratings.asStateFlow()

    fun catalogChanged(mediaType: MediaType) {
        _catalog.value = _catalog.value + (mediaType to _catalog.value.getValue(mediaType) + 1L)
    }

    fun ratingsChanged(mediaType: MediaType) {
        _ratings.value = _ratings.value + (mediaType to _ratings.value.getValue(mediaType) + 1L)
    }
}
