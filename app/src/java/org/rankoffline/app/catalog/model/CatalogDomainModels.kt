package org.rankoffline.app

/** The same item/group ID may exist in different packs. Never use an unqualified ID as a key. */
enum class CatalogEntryKind { ITEM, GROUP }

data class CatalogEntryKey(
    val packId: PackId,
    val id: String,
    val kind: CatalogEntryKind
) {
    init {
        require(id.isNotBlank()) { "Catalog entry ID must not be blank" }
    }
}

/** Built-in sorting operations. */
enum class CatalogSortOrder { POPULARITY, POPULARITY_REVERSE, TITLE_ASC, TITLE_DESC }

val CatalogSortOrder.isReversed: Boolean
    get() = this == CatalogSortOrder.POPULARITY_REVERSE || this == CatalogSortOrder.TITLE_DESC

fun CatalogSortOrder.withReversed(reversed: Boolean): CatalogSortOrder = when (this) {
    CatalogSortOrder.POPULARITY,
    CatalogSortOrder.POPULARITY_REVERSE ->
        if (reversed) CatalogSortOrder.POPULARITY_REVERSE else CatalogSortOrder.POPULARITY
    CatalogSortOrder.TITLE_ASC,
    CatalogSortOrder.TITLE_DESC ->
        if (reversed) CatalogSortOrder.TITLE_DESC else CatalogSortOrder.TITLE_ASC
}

fun CatalogSortOrder.inverted(): CatalogSortOrder = withReversed(!isReversed)

data class CatalogAttributeSort(val fieldId: String, val descending: Boolean = false) {
    init {
        require(fieldId.isNotBlank()) { "Sort field ID must not be blank" }
    }
}

enum class RankingSortOrder { FINAL_SCORE, QUALITY_SCORE, PERSONAL_TASTE, CATEGORY_SCORE }

data class RatingScores(
    val finalScore: Double? = null,
    val qualityScore: Double? = null,
    val additiveScore: Double? = null,
    val categoryScore: Double? = null
) {
    fun displayScore(sortOrder: RankingSortOrder): Double? = when (sortOrder) {
        RankingSortOrder.FINAL_SCORE -> finalScore
        RankingSortOrder.QUALITY_SCORE -> qualityScore
        RankingSortOrder.PERSONAL_TASTE -> additiveScore?.times(10.0)
        RankingSortOrder.CATEGORY_SCORE -> categoryScore
    }?.takeIf { it.isFinite() && it in RATING_MIN..RATING_MAX }
}

/** Attribute values in a pack: meaning, type, cardinality and allowed values come from its manifest. */
sealed interface CatalogAttributeValue {
    data class Text(val value: String) : CatalogAttributeValue
    data class Number(val value: Double) : CatalogAttributeValue {
        init { require(value.isFinite()) { "Attribute number must be finite" } }
    }
    data class Flag(val value: Boolean) : CatalogAttributeValue
}

/** Selections are keyed by manifest filter ID. */
sealed interface CatalogFilterValue {
    val isActive: Boolean

    data class Choices(val values: Set<String>) : CatalogFilterValue {
        override val isActive: Boolean get() = values.isNotEmpty()
    }

    data class Range(val min: Double? = null, val max: Double? = null) : CatalogFilterValue {
        init {
            require(min == null || min.isFinite()) { "Range minimum must be finite" }
            require(max == null || max.isFinite()) { "Range maximum must be finite" }
            require(min == null || max == null || min <= max) { "Invalid numeric range" }
        }
        override val isActive: Boolean get() = min != null || max != null
    }

    /** Dates use ISO-8601 strings. The pack importer validates calendar correctness. */
    data class DateRange(val from: String? = null, val to: String? = null) : CatalogFilterValue {
        init { require(from == null || to == null || from <= to) { "Invalid date range" } }
        override val isActive: Boolean get() = from != null || to != null
    }

    data class BooleanEquals(val value: Boolean) : CatalogFilterValue {
        override val isActive: Boolean get() = true
    }
}

data class CatalogFilters(val selections: Map<String, CatalogFilterValue> = emptyMap()) {
    val activeCount: Int get() = selections.values.count { it.isActive }
}

