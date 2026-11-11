package org.rankoffline.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Finalize a new installation interrupted after file promotion and before Room commit. */
suspend fun PackManager.recover(packId: PackId): Boolean = packLock(packId).withLock {
    val directory = installer.installedDirectory(packId)
    val summary = summaries.read(packId)
    if (directory != null && !installer.hasPendingInstallation(packId) &&
        !installer.hasPendingDeletion(packId) && summary?.catalogReady == true &&
        summary.matches(directory)) {
        val manifest = readInstalledManifest(directory)
        if (manifest != null && isCompatiblePackVersion(manifest.minRankOfflineVersion,
                currentAppVersion) && synchronizer.isCurrentFast(manifest, directory)) {
            runtime[packId] = PackRuntimeStatus.READY
            refresh()
            return@withLock true
        }
    }
    runtime[packId] = PackRuntimeStatus.INITIALIZING
    try {
        val ok = PackCoverWriteLocks.withPackLock(packId) {
            summaries.markAllDirty(packId) // Before recovery mutates files or Room.
            val phase = withContext(Dispatchers.IO) { installer.recover(packId) }
            val directory = installer.installedDirectory(packId)
            if (directory != null) {
                val manifest = requireNotNull(readInstalledManifest(directory))
                require(isCompatiblePackVersion(manifest.minRankOfflineVersion, currentAppVersion))
                withImportTurn { synchronizer.sync(manifest, directory) }
                if (phase == PackRecoveryState.AWAITING_COMMIT) withContext(Dispatchers.IO) {
                    installer.commitInstallation(packId)
                }
            } else withImportTurn { catalogCleaner.clear(packId) }
            true
        }
        runtime[packId] = if (installer.installedDirectory(packId) != null)
            PackRuntimeStatus.READY else PackRuntimeStatus.NOT_INSTALLED
        runCatching {
            installer.installedDirectory(packId)?.let { summaries.markCatalogReady(packId, it) }
        }
        ok
    } catch (cancelled: CancellationException) {
        runtime[packId] = PackRuntimeStatus.FILES_INSTALLED
        throw cancelled
    } catch (_: Exception) {
        runtime[packId] = PackRuntimeStatus.ERROR
        false
    } finally {
        tracker.clear(packId)
        withContext(NonCancellable) { refresh() }
    }
}


suspend fun PackManager.recoverAll(): Map<PackId, Boolean> =
    discoverIds().associateWith { recover(it) }


/** Publish deletion before cancelling cover work or waiting for the pack lock. */
internal suspend fun PackManager.markDeleting(packId: PackId) = refreshMutex.withLock {
    runtime[packId] = PackRuntimeStatus.DELETING
    synchronized(stateLock) {
        _states.value[packId]?.let { state ->
            _states.value = _states.value + (packId to state.copy(status = PackRuntimeStatus.DELETING))
        }
    }
}

/** Restore the installed state if preparation failed before deletion began. */
internal suspend fun PackManager.cancelDeleting(packId: PackId) {
    if (runtime.remove(packId, PackRuntimeStatus.DELETING)) refresh()
}


suspend fun PackManager.delete(packId: PackId): Boolean = packLock(packId).withLock {
    runtime[packId] = PackRuntimeStatus.DELETING
    try {
        val ok = PackCoverWriteLocks.withPackLock(packId) {
            summaries.markAllDirty(packId) // Before deletion or catalog cleanup.
            withImportTurn { catalogCleaner.clear(packId) }
            withContext(Dispatchers.IO) { installer.deleteInstalledPack(packId) }
        }
        val filesGone = ok
        if (filesGone) runCatching { summaries.markUninstalled(packId) }
        runtime[packId] = if (filesGone) PackRuntimeStatus.NOT_INSTALLED else PackRuntimeStatus.ERROR
        filesGone
    } catch (cancelled: CancellationException) {
        runtime[packId] = PackRuntimeStatus.ERROR
        throw cancelled
    } catch (_: Exception) {
        runtime[packId] = PackRuntimeStatus.ERROR
        false
    } finally { withContext(NonCancellable) { refresh() } }
}


/** Optional first-100 cover batch. WorkManager owns its lifecycle; NO persistent operation status. */
suspend fun PackManager.acquireInitialCovers(
    packId: PackId,
    target: Int,
    acquire: suspend (progress: (CoverDownloadProgress) -> Unit) -> CoverDownloadProgress
): CoverDownloadProgress = packLock(packId).withLock {
    require(target in 1..5_000)
    check(installer.installedDirectory(packId) != null && runtime[packId] == PackRuntimeStatus.READY)
    // The requested target is known before selection; show a stable 0/N immediately.
    tracker.set(packId, PackInstallProgress(PackInstallPhase.DOWNLOADING_COVERS,
        coverTarget = target))
    try {
        acquire { progress ->
            if (progress.status == CoverDownloadStatus.WAITING_TO_SAVE) {
                tracker.phase(packId, PackInstallPhase.WAITING_TO_SAVE_COVERS)
            } else if (progress.target > 0 && progress.status != CoverDownloadStatus.COMPLETED) {
                tracker.phase(packId, PackInstallPhase.DOWNLOADING_COVERS)
            }
            tracker.covers(packId, progress)
        }
    } finally {
        tracker.clear(packId)
        // Individual cover operations already invalidated their precise fields.
    }
}


/** Clear the temporary indicator after WorkManager accepts cancellation of initial covers. */
internal fun PackManager.clearCancelledInitialCoverProgress(packId: PackId) {
    if (tracker.progress.value[packId]?.phase == PackInstallPhase.DOWNLOADING_COVERS)
        tracker.clear(packId)
}

internal fun PackManager.discoverIds(): Set<PackId> = packRoot.listFiles().orEmpty().mapNotNull { file ->
    val value = when {
        file.isDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
            !file.name.startsWith('.') -> file.name
        file.name.endsWith(".pending") -> file.name.removePrefix(".").removeSuffix(".pending")
        file.name.endsWith(".deleting") -> file.name.removePrefix(".").removeSuffix(".deleting")
        else -> PackManager.STAGING_NAME.matchEntire(file.name)?.groupValues?.get(1)
    } ?: return@mapNotNull null
    PackId.fromDirectoryName(value)
}.toSet()
