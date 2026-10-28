package org.rankoffline.app

data class SearchUiState(
    val query: String = "",
    val sortOrder: CatalogSortOrder = CatalogSortOrder.POPULARITY,
    val filters: AnimeFilters = AnimeFilters(),
    val genericFilters: GenericMediaFilters = GenericMediaFilters()
)

data class SearchPageAccumulator<T>(
    val items: List<T> = emptyList(),
    val nextOffset: Int = 0,
    val hasMore: Boolean = true
) {
    fun append(page: List<T>, requestedSize: Int, identity: (T) -> String): SearchPageAccumulator<T> {
        require(requestedSize > 0)
        return SearchPageAccumulator(
            items = (items + page).distinctBy(identity),
            nextOffset = nextOffset + page.size,
            hasMore = page.size == requestedSize
        )
    }
}

enum class BottomNavigationAction { SWITCH, RESELECT }

internal fun bottomNavigationAction(current: Int, target: Int): BottomNavigationAction =
    if (current == target) BottomNavigationAction.RESELECT else BottomNavigationAction.SWITCH

data class RankingUiState(
    val sortOrder: RankingSortOrder = RankingSortOrder.FINAL_SCORE,
    val descending: Boolean = true,
    val categoryId: String? = null,
    val filters: AnimeFilters = AnimeFilters(),
    val genericFilters: GenericMediaFilters = GenericMediaFilters()
)

data class GenericMediaFilters(
    val genre: String? = null,
    val format: String? = null,
    val year: Int? = null,
    val creditName: String? = null,
    val demographic: String? = null
) {
    val activeCount: Int get() = listOf(genre, format, year, creditName, demographic).count { it != null }
}

data class GenericMediaFilterOptions(
    val genres: List<String> = emptyList(),
    val formats: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
    val creditNames: List<String> = emptyList(),
    val demographics: List<String> = emptyList()
)

internal fun AnimeFilters.toGenericMediaFilters(): GenericMediaFilters = GenericMediaFilters(
    genre = genre,
    format = format,
    year = year,
    creditName = studio
)

fun MediaType.genericFilterCreditRole(): String? = when (this) {
    MediaType.ANIME -> "STUDIO"
    MediaType.MANGA -> "AUTHOR"
    MediaType.MOVIE -> "PRODUCTION_COMPANY"
    MediaType.SERIES -> "NETWORK"
    MediaType.BOOK -> "AUTHOR"
    else -> null
}

fun MediaType.genericGroupType(): String? = when (this) {
    MediaType.ANIME -> "RELATIONS"
    MediaType.MANGA -> "VARIANTS"
    MediaType.SERIES -> "SEASONS"
    MediaType.MOVIE -> "COLLECTION"
    MediaType.BOOK -> "BOOK_SERIES"
    else -> null
}

fun MediaType.supportsMerge(): Boolean = genericGroupType() != null

fun catalogSortCriteria(): List<CatalogSortOrder> =
    listOf(CatalogSortOrder.POPULARITY, CatalogSortOrder.TITLE_ASC)

internal fun defaultSearchUiStates(): Map<MediaType, SearchUiState> =
    MediaType.entries.associateWith { SearchUiState(sortOrder = CatalogSortOrder.POPULARITY) }

internal fun Map<MediaType, SearchUiState>.withCatalogSortOrders(
    sortOrders: Map<MediaType, CatalogSortOrder>
): Map<MediaType, SearchUiState> = MediaType.entries.associateWith { mediaType ->
    searchStateFor(mediaType).copy(
        sortOrder = sortOrders[mediaType] ?: CatalogSortOrder.POPULARITY
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

/** Categories are comparable in All only when every participating pack defines the same concept. */
internal fun compatibleAllRatingCategories(
    mediaTypes: Collection<MediaType>,
    settings: Map<MediaType, MediaSettings>
): List<RatingCategory> {
    val first = mediaTypes.firstOrNull() ?: return emptyList()
    val remaining = mediaTypes.drop(1)
    return settings.getValue(first).ratingConfig.categories.filter { candidate ->
        candidate.active && remaining.all { mediaType ->
            settings.getValue(mediaType).ratingConfig.categories.any { category ->
                category.active && category.id == candidate.id &&
                    category.name.trim().equals(candidate.name.trim(), ignoreCase = true)
            }
        }
    }
}
