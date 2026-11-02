package org.rankoffline.app

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

fun CoverRepository.invalidatePackRuntimeState(packId: PackId) {
    states.keys.filter { it.packId == packId }.forEach { key ->
        states.remove(key)?.value = CoverDownloadState.IDLE
    }
}


/** Number of different valid files referenced by entries (item or group). */
fun CoverRepository.observeLocalCoverCount(packId: PackId): Flow<Int> {
    val (items, groups) = records.observePaths(packId)
    return combine(items, groups) { itemPaths, groupPaths ->
        (itemPaths + groupPaths).asSequence().map(::File)
            .filter { storage.owns(packId, it) && CoverImageFiles.isValid(it) }
            .map { it.absolutePath }.distinct().count()
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
}


fun CoverRepository.hasValidLocalCover(item: MediaItem): Boolean =
    validLocal(item.key, item.remoteCoverRef, item.localCoverPath)


fun CoverRepository.hasValidLocalCover(group: MediaGroup): Boolean =
    validLocal(group.key, group.remoteCoverRef, group.localCoverPath)


suspend fun CoverRepository.ratedCoverItems(packId: PackId, limit: Int): List<MediaItem> = withContext(Dispatchers.IO) {
    require(limit > 0)
    records.ratedItems(packId, limit)
}


suspend fun CoverRepository.topPopularCoverItems(packId: PackId, limit: Int): List<MediaItem> =
    withContext(Dispatchers.IO) {
        require(limit > 0)
        records.popularItems(packId, limit)
    }
