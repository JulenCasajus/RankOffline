package org.rankoffline.app

import java.io.File

fun LocalPackSummaryStore.recordBatch(packId: PackId, progress: CoverDownloadProgress) = update(packId) {
    if (progress.status == CoverDownloadStatus.CANCELLED) it else it.copy(
        lastBatchTarget = progress.target, lastBatchDownloaded = progress.downloaded,
        lastBatchUnavailable = progress.unavailable,
        lastBatchErrors = if (progress.status == CoverDownloadStatus.FAILED)
            maxOf(progress.errors, 1) else progress.errors,
        updatedAt = System.currentTimeMillis())
}


/** Count/copy operations happen on demand, never in PackManager.refresh(). */
fun LocalPackSummaryStore.recordStorage(packId: PackId, directory: File, installed: Long, covers: Long) = update(packId) {
    it.copy(installedBytes = installed, coverBytes = covers,
        dirty = it.dirty - PackSummaryField.INSTALLED_BYTES - PackSummaryField.COVER_BYTES,
        manifestSize = File(directory, PackInstaller.PACK_MANIFEST).length(),
        manifestModified = File(directory, PackInstaller.PACK_MANIFEST).lastModified(),
        completeModified = File(directory, PackInstaller.COMPLETE_MARKER).lastModified(),
        catalogModified = File(directory, "catalog").lastModified(),
        relationsModified = File(directory, "groups").lastModified(),
        coversModified = File(directory, "covers").lastModified(),
        itemsCoverModified = File(directory, "covers/items").lastModified(),
        groupsCoverModified = File(directory, "covers/groups").lastModified(),
        updatedAt = System.currentTimeMillis())
}


fun LocalPackSummaryStore.markCatalogReady(packId: PackId, directory: File) = update(packId) {
    it.copy(catalogReady = true,
        manifestSize = File(directory, PackInstaller.PACK_MANIFEST).length(),
        manifestModified = File(directory, PackInstaller.PACK_MANIFEST).lastModified(),
        completeModified = File(directory, PackInstaller.COMPLETE_MARKER).lastModified(),
        catalogModified = File(directory, "catalog").lastModified(),
        relationsModified = File(directory, "groups").lastModified())
}


fun LocalPackSummaryStore.recordItemCount(packId: PackId, count: Long) = update(packId) {
    it.copy(itemCount = count, dirty = it.dirty - PackSummaryField.ITEM_COUNT,
        updatedAt = System.currentTimeMillis())
}


fun LocalPackSummaryStore.recordGroupCount(packId: PackId, count: Long) = update(packId) {
    it.copy(groupCount = count, dirty = it.dirty - PackSummaryField.GROUP_COUNT,
        updatedAt = System.currentTimeMillis())
}


fun LocalPackSummaryStore.recordAvailableCovers(packId: PackId, count: Long) = update(packId) {
    it.copy(availableCoverCount = count,
        dirty = it.dirty - PackSummaryField.AVAILABLE_COVER_COUNT,
        updatedAt = System.currentTimeMillis())
}


/** NOT_FOUND identities are stored on the respective Room row; this caches their SQL COUNT. */
fun LocalPackSummaryStore.recordMissingCovers(packId: PackId, count: Long) = update(packId) {
    it.copy(missingCoverCount = count,
        dirty = it.dirty - PackSummaryField.MISSING_COVER_COUNT,
        updatedAt = System.currentTimeMillis())
}


/** Don't erase local user files or preferences during package uninstall. */
fun LocalPackSummaryStore.markUninstalled(packId: PackId) = update(packId) {
    it.copy(installedBytes = 0, coverBytes = 0, itemCount = 0, groupCount = 0,
        availableCoverCount = 0, missingCoverCount = 0,
        dirty = PackSummaryField.entries.toSet(), catalogReady = false,
        manifestSize = -1, manifestModified = -1, completeModified = -1,
        catalogModified = -1, relationsModified = -1,
        coversModified = -1, itemsCoverModified = -1, groupsCoverModified = -1,
        lastBatchTarget = 0, lastBatchDownloaded = 0,
        lastBatchUnavailable = 0, lastBatchErrors = 0)
}
