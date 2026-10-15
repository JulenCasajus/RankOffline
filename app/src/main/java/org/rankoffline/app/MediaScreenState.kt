package org.rankoffline.app

data class SearchUiState(
    val query: String = "",
    val sortOrder: CatalogSortOrder = CatalogSortOrder.POPULARITY,
    val filters: AnimeFilters = AnimeFilters(),
    val genericFilters: GenericMediaFilters = GenericMediaFilters()
)

data class RankingUiState(
    val sortOrder: RankingSortOrder = RankingSortOrder.FINAL_SCORE,
    val descending: Boolean = true,
    val categoryId: String? = null,
    val filters: AnimeFilters = AnimeFilters(),
    val genericFilters: GenericMediaFilters = GenericMediaFilters()
)

data class GenericMediaFilters(
    val genre: String? = null,
    val year: Int? = null,
    val creditName: String? = null,
    val demographic: String? = null
) {
    val activeCount: Int get() = listOf(genre, year, creditName, demographic).count { it != null }
}

data class GenericMediaFilterOptions(
    val genres: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
    val creditNames: List<String> = emptyList(),
    val demographics: List<String> = emptyList()
)

enum class MediaDataPath { ANIME, GENERIC, UNSUPPORTED }

fun MediaType.dataPath(): MediaDataPath = when (this) {
    MediaType.ANIME -> MediaDataPath.ANIME
    MediaType.MANGA, MediaType.MOVIE, MediaType.SERIES, MediaType.BOOK -> MediaDataPath.GENERIC
}

fun MediaType.genericFilterCreditRole(): String? = when (this) {
    MediaType.MOVIE -> "PRODUCTION_COMPANY"
    MediaType.SERIES -> "NETWORK"
    MediaType.BOOK -> "AUTHOR"
    else -> null
}

fun MediaType.genericGroupType(): String? = when (this) {
    MediaType.MANGA -> "VARIANTS"
    MediaType.SERIES -> "SEASONS"
    MediaType.MOVIE -> "COLLECTION"
    else -> null
}

fun MediaType.supportsMerge(): Boolean = this != MediaType.BOOK

fun MediaType.catalogSortCriteria(): List<CatalogSortOrder> = when (this) {
    MediaType.BOOK -> listOf(CatalogSortOrder.TITLE_ASC)
    else -> listOf(CatalogSortOrder.POPULARITY, CatalogSortOrder.TITLE_ASC)
}

internal fun defaultSearchUiStates(): Map<MediaType, SearchUiState> =
    MediaType.entries.associateWith { mediaType ->
        SearchUiState(
            sortOrder = if (mediaType == MediaType.MANGA || mediaType == MediaType.BOOK) {
                CatalogSortOrder.TITLE_ASC
            } else {
                CatalogSortOrder.POPULARITY
            }
        )
    }

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
