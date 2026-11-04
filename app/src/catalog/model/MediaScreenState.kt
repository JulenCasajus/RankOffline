package org.rankoffline.app

/** Search preferences for one pack, or an independently managed combined view. */
data class SearchUiState(
    val query: String = "",
    val sortOrder: CatalogSortOrder = CatalogSortOrder.POPULARITY,
    /** Selections are keyed by the filter IDs declared in the containing .pack. */
    val filters: CatalogFilters = CatalogFilters()
)

/**
 * Offset pagination. A page's full size advances the offset even when rows repeat.
 * A complete catalog identity avoids collisions between packs and item/group entries.
 */
data class SearchPageAccumulator<T>(
    val items: List<T> = emptyList(),
    val nextOffset: Int = 0,
    val hasMore: Boolean = true
) {
    init {
        require(nextOffset >= 0) { "Negative search offset" }
    }

    fun append(
        page: List<T>,
        requestedSize: Int,
        identity: (T) -> CatalogEntryKey
    ): SearchPageAccumulator<T> {
        require(requestedSize > 0) { "Page size must be positive" }
        require(page.size <= requestedSize) { "Received more rows than requested" }
        require(nextOffset <= Int.MAX_VALUE - page.size) { "Search offset overflow" }

        val seen = items.mapTo(HashSet<CatalogEntryKey>(), identity)
        val newItems = page.filter { seen.add(identity(it)) }
        return SearchPageAccumulator(
            items = items + newItems,
            nextOffset = nextOffset + page.size,
            hasMore = page.size == requestedSize
        )
    }
}

enum class BottomNavigationAction { SWITCH, RESELECT }

internal fun bottomNavigationAction(current: Int, target: Int): BottomNavigationAction =
    if (current == target) BottomNavigationAction.RESELECT else BottomNavigationAction.SWITCH

/** Ranking preferences; ratingCategoryId is a user-defined scoring criterion, not a pack category. */
data class RankingUiState(
    val sortOrder: RankingSortOrder = RankingSortOrder.FINAL_SCORE,
    val descending: Boolean = true,
    val ratingCategoryId: String? = null,
    val filters: CatalogFilters = CatalogFilters()
)

/** Primary sort options; reverse directions are controlled by CatalogSortOrder. */
fun catalogSortCriteria(hasPopularity: Boolean = true): List<CatalogSortOrder> =
    if (hasPopularity) {
        listOf(CatalogSortOrder.POPULARITY, CatalogSortOrder.TITLE_ASC)
    } else {
        listOf(CatalogSortOrder.TITLE_ASC)
    }

private fun CatalogSortOrder.supportedBy(manifest: PackManifest): Boolean =
    manifest.capabilities.popularity ||
        this == CatalogSortOrder.TITLE_ASC || this == CatalogSortOrder.TITLE_DESC

/**
 * Ignore stale or incompatible selections when a pack changes its declared filter schema.
 * This never changes the package's catalog data or the user's stored rating criteria.
 */
private fun CatalogFilters.validFor(manifest: PackManifest): CatalogFilters {
    if (!manifest.capabilities.filters) return CatalogFilters()
    val fields = manifest.fields.associateBy(PackFieldDefinition::id)
    val definitions = manifest.filters.associateBy(PackFilterDefinition::id)
    return CatalogFilters(selections.filter { (id, selection) ->
        val definition = definitions[id] ?: return@filter false
        val field = fields[definition.field] ?: return@filter false
        when (definition.type) {
            PackFilterType.MULTI_SELECT -> when (field.type) {
                CatalogFieldType.TEXT -> selection is CatalogFilterValue.Choices
                CatalogFieldType.CHOICE -> selection is CatalogFilterValue.Choices &&
                    (field.options.isEmpty() || selection.values.all { it in field.options })
                CatalogFieldType.BOOLEAN -> selection is CatalogFilterValue.BooleanEquals
                else -> false
            }
            PackFilterType.RANGE -> when (field.type) {
                CatalogFieldType.INTEGER, CatalogFieldType.DECIMAL -> selection is CatalogFilterValue.Range
                CatalogFieldType.DATE -> selection is CatalogFilterValue.DateRange
                else -> false
            }
        }
    })
}

/** Apply the current pack capabilities without resetting unrelated UI preferences. */
internal fun SearchUiState.forManifest(manifest: PackManifest): SearchUiState = copy(
    sortOrder = sortOrder.takeIf { it.supportedBy(manifest) }
        ?: catalogSortCriteria(manifest.capabilities.popularity).first(),
    filters = filters.validFor(manifest)
)

internal fun RankingUiState.forManifest(manifest: PackManifest): RankingUiState =
    copy(filters = filters.validFor(manifest))

/** Only known manifests create pack-specific state; no hardcoded category list. */
internal fun defaultSearchUiStates(manifests: Collection<PackManifest>): Map<PackId, SearchUiState> =
    manifests.associate { it.packId to SearchUiState().forManifest(it) }

internal fun defaultRankingUiStates(manifests: Collection<PackManifest>): Map<PackId, RankingUiState> =
    manifests.associate { it.packId to RankingUiState() }

/** Clear an obsolete scoring criterion, restoring a meaningful ranking mode if necessary. */
internal fun RankingUiState.withValidRatingCategory(validIds: Set<String>): RankingUiState {
    if (ratingCategoryId != null && ratingCategoryId in validIds) return this
    return copy(
        ratingCategoryId = null,
        sortOrder = if (sortOrder == RankingSortOrder.CATEGORY_SCORE) {
            RankingSortOrder.FINAL_SCORE
        } else {
            sortOrder
        }
    )
}

/**
 * RankOfflineAll can compare rating criteria only if every selected pack uses the
 * same active criterion ID and name. Missing settings never throw.
 */
internal fun compatibleAllRatingCategories(
    packIds: Collection<PackId>,
    settings: Map<PackId, MediaSettings>
): List<RatingCategory> {
    val packs = packIds.distinct()
    val first = packs.firstOrNull() ?: return emptyList()
    val firstCategories = settings[first]?.ratingConfig?.categories ?: return emptyList()
    val remainingCategories = packs.drop(1).map { packId ->
        settings[packId]?.ratingConfig?.categories ?: return emptyList()
    }
    return firstCategories.asSequence()
        .filter(RatingCategory::active)
        .distinctBy(RatingCategory::id)
        .filter { candidate ->
            remainingCategories.all { categories ->
                categories.any { category ->
                    category.active && category.id == candidate.id &&
                        category.name.trim().equals(candidate.name.trim(), ignoreCase = true)
                }
            }
        }.toList()
}
