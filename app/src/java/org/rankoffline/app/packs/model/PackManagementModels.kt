package org.rankoffline.app

/** Runtime state of the catalog, independent from network transport and content category. */
enum class PackRuntimeStatus {
    NOT_INSTALLED, FILES_INSTALLED, INITIALIZING, READY, DELETING, ERROR
}

enum class PackInstallPhase {
    QUEUED, DOWNLOADING, VERIFYING, INSTALLING, WAITING_IMPORT, IMPORTING,
    FINALIZING, WAITING_COVERS, DOWNLOADING_COVERS
}

enum class PackOperationResult {
    INSTALLED, NOT_FOUND, NETWORK_ERROR, INVALID_PACK, IMPORT_ERROR, STORAGE_ERROR
}

data class PackInstallProgress(
    val phase: PackInstallPhase,
    val downloadedBytes: Long = 0,
    val totalDownloadBytes: Long = 0,
    val coverAvailable: Int = 0,
    val coverTarget: Int = 0
) {
    val downloadFraction: Float get() = if (totalDownloadBytes <= 0) 0f else
        (downloadedBytes.toDouble() / totalDownloadBytes).coerceIn(0.0, 1.0).toFloat()
}

data class PackAggregateProgress(
    val downloadedBytes: Long,
    val totalDownloadBytes: Long,
    val transferCount: Int
)

data class PackStorageSnapshot(
    val installedBytes: Long,
    val coverBytes: Long,
    val residualBytes: Long = 0L,
    /** False means figures come from a stale or absent local summary, not a fresh scan. */
    val current: Boolean = false
) {
    /** coverBytes is already included in installedBytes. */
    val totalBytes: Long get() = Math.addExact(installedBytes, residualBytes)
}

data class PackState(
    val packId: PackId,
    val manifest: PackManifest?,
    val sourceAvailable: Boolean,
    val installed: Boolean,
    val status: PackRuntimeStatus,
    val archiveDownloadBytes: Long?,
    val storage: PackStorageSnapshot,
    val progress: PackInstallProgress? = null,
    val hasPendingFiles: Boolean = false
) {
    val available: Boolean get() = installed && status == PackRuntimeStatus.READY
    val canDownload: Boolean get() = sourceAvailable && !installed && progress == null &&
        status != PackRuntimeStatus.INITIALIZING && status != PackRuntimeStatus.DELETING
    val canDelete: Boolean get() = progress == null && status != PackRuntimeStatus.DELETING &&
        (installed || hasPendingFiles)
}
