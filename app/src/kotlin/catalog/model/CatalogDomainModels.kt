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

enum class RankingSortOrder { FINAL_SCORE, QUALITY_SCORE, PERSONAL_TASTE, CATEGORY_SCORE }
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

    /** AND matches every selected value; OR matches any selected value. */
    data class Choices(val values: Set<String>, val matchAll: Boolean = false) : CatalogFilterValue {
        override val isActive: Boolean get() = values.isNotEmpty()
    }

}

data class CatalogFilters(val selections: Map<String, CatalogFilterValue> = emptyMap()) {
    val activeCount: Int get() = selections.values.count { it.isActive }
}

