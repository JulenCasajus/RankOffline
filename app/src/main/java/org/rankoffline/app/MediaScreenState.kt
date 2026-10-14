package org.rankoffline.app

data class SearchUiState(
    val query: String = "",
    val sortOrder: CatalogSortOrder = CatalogSortOrder.POPULARITY,
    // Temporary compatibility until each catalog introduces its own filter model.
    val filters: AnimeFilters = AnimeFilters()
)

data class RankingUiState(
    val sortOrder: RankingSortOrder = RankingSortOrder.FINAL_SCORE,
    val descending: Boolean = true,
    val categoryId: String? = null,
    // Temporary compatibility until each catalog introduces its own filter model.
    val filters: AnimeFilters = AnimeFilters()
)

internal fun defaultSearchUiStates(): Map<MediaType, SearchUiState> =
    MediaType.entries.associateWith { SearchUiState() }

internal fun defaultRankingUiStates(): Map<MediaType, RankingUiState> =
    MediaType.entries.associateWith { RankingUiState() }

internal fun Map<MediaType, SearchUiState>.searchStateFor(mediaType: MediaType): SearchUiState =
    this[mediaType] ?: SearchUiState()

internal fun Map<MediaType, RankingUiState>.rankingStateFor(mediaType: MediaType): RankingUiState =
    this[mediaType] ?: RankingUiState()

internal fun Map<MediaType, SearchUiState>.updateSearchUiState(
    mediaType: MediaType,
    transform: (SearchUiState) -> SearchUiState
): Map<MediaType, SearchUiState> = this + (mediaType to transform(searchStateFor(mediaType)))

internal fun Map<MediaType, RankingUiState>.updateRankingUiState(
    mediaType: MediaType,
    transform: (RankingUiState) -> RankingUiState
): Map<MediaType, RankingUiState> = this + (mediaType to transform(rankingStateFor(mediaType)))

internal fun RankingUiState.withValidCategory(categoryIds: Set<String>): RankingUiState {
    val selected = categoryId ?: return this
    if (selected in categoryIds) return this
    return copy(
        sortOrder = if (sortOrder == RankingSortOrder.CATEGORY_SCORE) {
            RankingSortOrder.FINAL_SCORE
        } else {
            sortOrder
        },
        categoryId = null
    )
}
