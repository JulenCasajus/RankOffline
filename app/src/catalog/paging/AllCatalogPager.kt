package org.rankoffline.app

typealias AllCatalogCursor = CombinedPageCursor

data class AllCatalogPage(
    val entries: List<MediaListEntry>,
    val nextCursor: AllCatalogCursor,
    val hasMore: Boolean
)

/** Sort orders beyond popularity need one candidate per pack and SQL-compatible ties. */
internal suspend fun readAllCatalogPage(
    manifests: List<PackManifest>,
    states: Map<PackId, SearchUiState>,
    mergeByPack: Map<PackId, Boolean>,
    limit: Int,
    cursor: AllCatalogCursor,
    repository: MediaRepository
): AllCatalogPage {
    val ids = manifests.map { it.packId }.distinct()
    if (ids.isEmpty()) return AllCatalogPage(emptyList(), cursor, false)
    val order = states.getValue(ids.first()).sortOrder
    require(ids.all { states.getValue(it).sortOrder == order })
    val popularity = order == CatalogSortOrder.POPULARITY || order == CatalogSortOrder.POPULARITY_REVERSE
    val ordering = Comparator<MediaListEntry> { a, b ->
        val title = sqliteNoCaseCompare(a.catalogTitle(), b.catalogTitle())
        val delta = if (order == CatalogSortOrder.TITLE_DESC) -title else title
        if (delta != 0) delta else compareValuesBy(a, b, { it.packId.value }, { it.kind.name }, { it.id })
    }
    val page = readCombinedPage(ids, limit, cursor, popularity, if (popularity) null else ordering) {
            id, offset, count ->
        repository.search(manifests.first { it.packId == id }, states.getValue(id),
            mergeByPack[id] == true, count, offset)
    }
    return AllCatalogPage(page.entries, page.nextCursor, page.hasMore)
}

/** SQLite NOCASE folds ASCII characters only. */
internal fun sqliteNoCaseCompare(a: String, b: String): Int {
    val left = a.toByteArray(Charsets.UTF_8)
    val right = b.toByteArray(Charsets.UTF_8)
    for (index in 0 until minOf(left.size, right.size)) {
        fun folded(value: Byte): Int = (value.toInt() and 255).let { if (it in 65..90) it + 32 else it }
        val cmp = folded(left[index]).compareTo(folded(right[index]))
        if (cmp != 0) return cmp
    }
    return left.size.compareTo(right.size)
}
private fun MediaListEntry.catalogTitle(): String = when (this) {
    is MediaListEntry.Item -> value.title
    is MediaListEntry.Group -> value.displayTitle
}
