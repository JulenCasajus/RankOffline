package org.rankoffline.app

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Top N DIFFERENT physical image identities, not the first N catalog rows.
 * Pages are read by popularity until N unique sources are collected or the catalog ends.
 * A group without its own usable cover maps to a representative ITEM, so it cannot
 * cause a second file to be downloaded under covers/groups/.
 */
suspend fun CoverRepository.topPopularVisibleCoverKeys(
    packId: PackId,
    limit: Int,
    mergeEnabled: Boolean,
    useGroupCoversForItems: Boolean
): List<CatalogEntryKey> = withContext(Dispatchers.IO) {
    require(limit in 1..5_000)
    val manifest = manifestProvider(packId)?.takeIf { it.packId == packId }
        ?: return@withContext emptyList()
    val groupSourceCache = HashMap<CatalogEntryKey, CatalogEntryKey?>()
    collectDistinctCoverKeys(
        limit = limit,
        loadPage = { offset, count ->
            catalogItems.visibleEntries(CatalogQueries.catalog(
                manifest, SearchUiState(sortOrder = CatalogSortOrder.POPULARITY),
                mergeEnabled, count, offset
            ))
        },
        sourceKeys = { rows ->
            val preferred = if (useGroupCoversForItems) {
                rows.asSequence().filter { it.entryKind == "ITEM" }.map { it.id }.distinct()
                    .toList().chunked(400).flatMap { ids ->
                        catalogGroups.preferredCoverGroups(packId.value, ids)
                    }.associate { it.mediaId to it.groupId }
            } else emptyMap()
            rows.map { row ->
                val logicalKey = when (row.entryKind) {
                    "GROUP" -> CatalogEntryKey(packId, row.id, CatalogEntryKind.GROUP)
                    "ITEM" -> preferred[row.id]?.let {
                        CatalogEntryKey(packId, it, CatalogEntryKind.GROUP)
                    } ?: CatalogEntryKey(packId, row.id, CatalogEntryKind.ITEM)
                    else -> error("Invalid entry kind: ${row.entryKind}")
                }
                if (logicalKey.kind == CatalogEntryKind.GROUP) {
                    if (groupSourceCache.containsKey(logicalKey)) groupSourceCache[logicalKey]
                    else bestGroupSourceKey(logicalKey).also { groupSourceCache[logicalKey] = it }
                } else logicalKey
            }
        }
    )
}


/** No duplicate bytes: group source only when its OWN file or source exists.
 * Otherwise the first valid local member, then the first member with a usable source. */
internal suspend fun CoverRepository.bestGroupSourceKey(groupKey: CatalogEntryKey): CatalogEntryKey? {
    val group = records.load(groupKey) ?: return null
    if (validLocal(groupKey, group.remoteRef, group.localPath) ||
        (group.remoteRef?.startsWith("covers/") == true &&
            declaredCover(groupKey.packId, group.remoteRef)
                ?.let(CoverImageFiles::isValid) == true)) return groupKey
    // A dedicated downloadable group cover outranks an already cached representative.
    if (group.state != CoverResolutionState.NOT_FOUND.name && group.candidates.isNotEmpty())
        return groupKey
    // No dedicated source, or it was definitively unavailable: reuse the first valid member.
    val fallback = fallbackKeys(groupKey)
    val availableItem = fallback.firstOrNull { itemKey ->
        val item = catalogItems.item(itemKey.packId.value, itemKey.id)
        item != null && validItemCoverPath(itemKey, item.remoteCoverRef,
            item.localCoverPath) != null
    }
    if (availableItem != null) return availableItem
    return fallback.firstOrNull()
}


internal suspend fun CoverRepository.fallbackKeys(groupKey: CatalogEntryKey): List<CatalogEntryKey> {
    val candidates = records.fallbackItems(groupKey)
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


/** User-requested single image: redirect grouped item images without touching their own cover state. */
suspend fun CoverRepository.displayedCoverKey(key: CatalogEntryKey, useGroupCoversForItems: Boolean): CatalogEntryKey =
    withContext(Dispatchers.IO) {
        if (!useGroupCoversForItems || key.kind != CatalogEntryKind.ITEM) return@withContext key
        val groupId = catalogGroups.preferredCoverGroups(key.packId.value, listOf(key.id))
            .firstOrNull()?.groupId ?: return@withContext key
        CatalogEntryKey(key.packId, groupId, CatalogEntryKind.GROUP)
    }


internal fun CoverRepository.declaredCover(packId: PackId, reference: String?): File? {
    if (reference == null) return null
    val manifest = manifestProvider(packId) ?: return null
    if (manifest.packId != packId || manifest.files.none { it.path == reference }) return null
    return storage.declaredFile(packId, reference)
}
