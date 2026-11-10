package org.rankoffline.app

/** Pure, testable rules shared by Search, Ranking and the filter dialog. */

internal fun CatalogFilters.withFilter(id: String, value: CatalogFilterValue?): CatalogFilters =
    copy(selections = if (value == null) selections - id else selections + (id to value))

/** Re-sort the union of per-pack options; each input list is sorted only within its pack. */
internal fun List<CatalogAttributeValue>.sortedFilterOptions(): List<CatalogAttributeValue> =
    distinct().sortedWith { left, right -> when {
        left is CatalogAttributeValue.Text && right is CatalogAttributeValue.Text ->
            left.value.compareTo(right.value, ignoreCase = true)
        left is CatalogAttributeValue.Number && right is CatalogAttributeValue.Number ->
            left.value.compareTo(right.value)
        left is CatalogAttributeValue.Flag && right is CatalogAttributeValue.Flag ->
            left.value.compareTo(right.value)
        else -> left.toString().compareTo(right.toString(), ignoreCase = true)
    } }

/** Only common, type-compatible filters are exposed in All; never assume an Anime schema. */
internal fun sharedFilterManifest(manifests: List<PackManifest>): PackManifest? {
    val first = manifests.firstOrNull() ?: return null
    val common = first.filters.filter { proposed ->
        manifests.drop(1).all { pack ->
            val matching = pack.filters.firstOrNull { it.id == proposed.id }
            val left = first.fields.firstOrNull { it.id == proposed.field }
            val right = pack.fields.firstOrNull { it.id == matching?.field }
            matching != null && matching.type == proposed.type &&
                left != null && right != null && left.type == right.type
        }
    }
    val used = common.mapTo(hashSetOf()) { it.field }
    return first.copy(filters = common, fields = first.fields.filter { it.id in used })
}
