package org.rankoffline.app

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Top N DIFFERENT physical image identities, not the first N catalog rows.
 * Pages are read by popularity until N unique sources are collected or the catalog ends.
 * A group without its own usable cover maps to a representative ITEM, so it cannot
 * cause a second file to be downloaded under covers/groups/.
 */
suspend fun CoverRepository.topPopularVisibleCoverKeys(
    packId: PackId,
    limit: Int,
    mergeEnabled: Boolean
): List<CatalogEntryKey> = withContext(Dispatchers.IO) {
    require(limit in 1..5_000)
    val manifest = manifestProvider(packId)?.takeIf { it.packId == packId }
        ?: return@withContext emptyList()
    // Read at most one bounded page of popular identities at a time.
    // Group/member fallback and remote resolution happen only during download.
    val merged = mergeEnabled && manifest.capabilities.merge && manifest.merge.enabled
    if (!merged) {
        return@withContext collectDistinctCoverKeys(
            limit = limit,
            loadPage = { offset, count -> catalogItems.topPopularCoverItemsPage(packId.value, count, offset) },
            sourceKeys = { rows -> rows.map { item ->
                CatalogEntryKey(packId, item.id, CatalogEntryKind.ITEM)
            } }
        )
    }
    collectDistinctCoverKeys(
        limit = limit,
        loadPage = { offset, count ->
            catalogItems.visibleEntries(CatalogQueries.popularCovers(manifest, count, offset))
        },
        sourceKeys = { rows -> rows.map { row ->
            CatalogEntryKey(packId, row.id, when (row.entryKind) {
                "GROUP" -> CatalogEntryKind.GROUP
                "ITEM" -> CatalogEntryKind.ITEM
                else -> error("Invalid entry kind: ${row.entryKind}")
            })
        } }
    )
}


internal suspend fun CoverRepository.fallbackKeys(groupKey: CatalogEntryKey): List<CatalogEntryKey> {
    return selectFallbackCandidates(groupKey, records.fallbackItems(groupKey))
}

/** One bounded relation read per page, including representative/member order. */
internal suspend fun CoverRepository.batchFallbackKeys(
    groupKeys: List<CatalogEntryKey>
): Map<CatalogEntryKey, List<CatalogEntryKey>> {
    if (groupKeys.isEmpty()) return emptyMap()
    val packId = groupKeys.first().packId
    require(groupKeys.all { it.packId == packId && it.kind == CatalogEntryKind.GROUP })
    val rows = groupKeys.map { it.id }.chunked(400).flatMap {
        catalogGroups.coverCandidateIds(packId.value, it)
    }
    val itemsById = rows.map { it.mediaId }.distinct().chunked(400).flatMap {
        catalogItems.itemsByIds(packId.value, it)
    }.associateBy { it.id }
    val byGroup = rows.groupBy({ it.groupId }, { itemsById[it.mediaId] }).mapValues { (_, items) ->
        items.filterNotNull()
    }
    return groupKeys.associateWith { key -> selectFallbackCandidates(key, byGroup[key.id].orEmpty()) }
}

private fun CoverRepository.selectFallbackCandidates(
    groupKey: CatalogEntryKey, candidates: List<MediaItemEntity>
): List<CatalogEntryKey> {
    val locallyAvailable = candidates.filter { item ->
        val itemKey = CatalogEntryKey(groupKey.packId, item.id, CatalogEntryKind.ITEM)
        validItemCoverPath(itemKey, item.remoteCoverRef, item.localCoverPath) != null
    }
    val remaining = candidates.filterNot { it in locallyAvailable }.filter { item ->
        item.coverResolutionState != CoverResolutionState.NOT_FOUND.name &&
            (item.remoteCoverRef?.startsWith("covers/") == true &&
                declaredCover(groupKey.packId, item.remoteCoverRef)
                    ?.let(CoverImageFiles::isValid) == true ||
                records.hasRemoteSource(item))
    }
    return (locallyAvailable + remaining).map {
        CatalogEntryKey(groupKey.packId, it.id, CatalogEntryKind.ITEM)
    }
}


internal fun CoverRepository.validItemCoverPath(key: CatalogEntryKey, ref: String?, path: String?): String? {
    val fromRoom = path?.takeIf { validLocal(key, ref, it) }
    if (fromRoom != null) return fromRoom
    // A pack-provided image can already exist before its path is set in Room.
    return if (ref?.startsWith("covers/") == true)
        declaredCover(key.packId, ref)?.takeIf(CoverImageFiles::isValid)?.absolutePath
    else null
}


internal fun CoverRepository.declaredCover(packId: PackId, reference: String?): File? {
    if (reference == null) return null
    val manifest = manifestProvider(packId) ?: return null
    if (manifest.packId != packId || manifest.files.none { it.path == reference }) return null
    return storage.declaredFile(packId, reference)
}
