package org.rankoffline.app

/** Pure, testable rules shared by Search, Ranking and the filter dialog. */

internal fun CatalogFilters.withFilter(id: String, value: CatalogFilterValue?): CatalogFilters =
    copy(selections = if (value == null) selections - id else selections + (id to value))

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
