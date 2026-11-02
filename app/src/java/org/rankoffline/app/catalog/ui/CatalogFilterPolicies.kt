package org.rankoffline.app

/** Pure, testable rules shared by Search, Ranking and the filter dialog. */


internal fun CatalogFilters.withFilter(id: String, value: CatalogFilterValue?): CatalogFilters =
    copy(selections = if (value == null) selections - id else selections + (id to value))

internal fun CatalogFilters.withNumericRange(id: String, rawMin: String, rawMax: String): CatalogFilters {
    val min = rawMin.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
    val max = rawMax.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
    if (min != null && !min.isFinite() || max != null && !max.isFinite()) return this
    if (min != null && max != null && min > max) return this
    return withFilter(id, if (min == null && max == null) null else CatalogFilterValue.Range(min, max))
}
internal fun CatalogFilters.withDateRange(id: String, rawFrom: String, rawTo: String): CatalogFilters {
    val from = rawFrom.trim().takeIf(String::isNotEmpty)
    val to = rawTo.trim().takeIf(String::isNotEmpty)
    if (from != null && to != null && from > to) return this
    return withFilter(id, if (from == null && to == null) null else CatalogFilterValue.DateRange(from, to))
}

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
