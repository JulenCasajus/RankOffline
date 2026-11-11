package org.rankoffline.app

import android.util.Log
import java.io.File
import java.io.FilterInputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Waiting imports yield to a short completed-cover write. An active import remains atomic. */
internal suspend fun <T> PackManager.withImportTurn(block: suspend () -> T): T {
    while (true) {
        pendingCoverCommits.first { it == 0 }
        importMutex.lock()
        if (pendingCoverCommits.value == 0) break
        importMutex.unlock()
    }
    try { return block() } finally { importMutex.unlock() }
}

internal suspend fun <T> PackManager.withCoverCommitTurn(onWaiting: () -> Unit, block: suspend () -> T): T {
    synchronized(coverCommitMonitor) { pendingCoverCommits.value += 1 }
    var acquired = false
    try {
        acquired = importMutex.tryLock()
        if (!acquired) {
            onWaiting()
            importMutex.lock()
            acquired = true
        }
        return block()
    } finally {
        if (acquired) importMutex.unlock()
        synchronized(coverCommitMonitor) { pendingCoverCommits.value -= 1 }
    }
}

suspend fun PackManager.markQueued(packId: PackId) {
    val descriptor = sources.descriptor(packId) ?: return
    if (installer.installedDirectory(packId) != null) return
    if (tracker.progress.value[packId] != null) return
    tracker.set(packId, PackInstallProgress(PackInstallPhase.QUEUED,
        totalDownloadBytes = descriptor.archiveBytes))
    refresh()
}


/** Keep in-memory progress aligned with WorkManager, even when the Worker never starts. */
suspend fun PackManager.reconcileQueuedInstallStates(
    active: Set<PackId>, failed: Set<PackId>
) {
    active.forEach { id ->
        if (tracker.progress.value[id] == null) markQueued(id)
    }
    val queued = tracker.progress.value.filterValues { it.phase == PackInstallPhase.QUEUED }.keys
    val expired = queued - active
    expired.forEach { id -> tracker.clear(id) }

    // WorkManager may fail before PackManager.downloadAndInstall() is ever entered.
    // Don't mistake historical failed jobs for a current failure or a successful install.
    var changed = expired.isNotEmpty()
    (failed - active).forEach { id ->
        if (runtime[id] != PackRuntimeStatus.READY &&
            runtime[id] != PackRuntimeStatus.ERROR && states.value[id]?.installed != true) {
            runtime[id] = PackRuntimeStatus.ERROR
            changed = true
        }
    }
    if (changed) refresh()
}


suspend fun PackManager.downloadAndInstall(packId: PackId): PackOperationResult = packLock(packId).withLock {
    val descriptor = sources.descriptor(packId) ?: return@withLock PackOperationResult.NOT_FOUND
    if (!isCompatiblePackVersion(descriptor.manifest.minRankOfflineVersion, currentAppVersion)) {
        return@withLock PackOperationResult.INVALID_PACK
    }
    alreadyInstalled(packId, descriptor.manifest)?.let { return@withLock it }
    val previous = runtime[packId] ?: PackRuntimeStatus.NOT_INSTALLED
    val archive = newTemporaryArchive(packId)
    runtime[packId] = PackRuntimeStatus.INITIALIZING
    tracker.set(packId, PackInstallProgress(PackInstallPhase.DOWNLOADING,
        totalDownloadBytes = descriptor.archiveBytes))
    try {
        val outcome = scheduler.withArchiveTransfer {
            downloader.download(descriptor, archive) { bytes ->
                tracker.downloaded(packId, bytes)
            }
        }
        if (outcome != PackOperationResult.INSTALLED) {
            runtime[packId] = previous
            return@withLock outcome
        }
        tracker.phase(packId, PackInstallPhase.VERIFYING)
        if (!PackArchiveReader.verifyArchiveDigest(archive, descriptor)) {
            runtime[packId] = previous
            return@withLock PackOperationResult.INVALID_PACK
        }
        val result = installVerified(packId, archive, descriptor.manifest)
        runtime[packId] = if (result == PackOperationResult.INSTALLED) PackRuntimeStatus.READY
            else PackRuntimeStatus.ERROR
        result
    } catch (cancelled: CancellationException) {
        runtime[packId] = previous
        throw cancelled
    } catch (failure: Exception) {
        Log.e("RankOfflinePack", "Download or installation failed: pack=${packId.value}", failure)
        runtime[packId] = PackRuntimeStatus.ERROR
        PackOperationResult.STORAGE_ERROR
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { deleteOwnedTree(archive) }
        tracker.clear(packId)
        withContext(NonCancellable) { refresh() }
    }
}


/** A repeated installation is a no-op only for the exact same manifest. */
internal fun PackManager.alreadyInstalled(id: PackId, expected: PackManifest): PackOperationResult? {
    if (installer.hasPendingInstallation(id) || installer.hasPendingDeletion(id))
        return PackOperationResult.STORAGE_ERROR
    val directory = installer.installedDirectory(id) ?: return null
    return if (readInstalledManifest(directory) == expected) PackOperationResult.INSTALLED
        else PackOperationResult.INVALID_PACK
}


internal suspend fun PackManager.installVerified(
    id: PackId, archive: File, expected: PackManifest
): PackOperationResult = PackCoverWriteLocks.withPackLock(id) {
    var promoted = false
    var imported = false
    try {
        summaries.markAllDirty(id) // Before any installer or Room write.
        val directory = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            PackArchiveReader.withVerifiedArchive(archive, expected) { request, open ->
                tracker.phase(id, PackInstallPhase.INSTALLING)
                installer.install(request) { spec ->
                    object : FilterInputStream(open(spec)) {
                        override fun read(): Int {
                            context.ensureActive()
                            return super.read()
                        }
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            context.ensureActive()
                            return super.read(buffer, offset, length)
                        }
                    }
                }
            }
        }
        promoted = true
        tracker.phase(id, PackInstallPhase.WAITING_IMPORT)
        withImportTurn {
            tracker.phase(id, PackInstallPhase.IMPORTING)
            val manifest = requireNotNull(readInstalledManifest(directory))
            require(manifest.packId == id)
            require(isCompatiblePackVersion(manifest.minRankOfflineVersion, currentAppVersion))
            synchronizer.sync(manifest, directory)
        }
        imported = true
        tracker.phase(id, PackInstallPhase.FINALIZING)
        withContext(Dispatchers.IO) { installer.commitInstallation(id) }
        // Advisory cache must never turn a successful Room commit into a failed install.
        runCatching { summaries.markCatalogReady(id, directory) }
        PackOperationResult.INSTALLED
    } catch (cancelled: CancellationException) {
        if (promoted && !imported) withContext(NonCancellable + Dispatchers.IO) {
            runCatching { installer.abortInstallation(id) }
        }
        throw cancelled
    } catch (failure: Exception) {
        Log.e("RankOfflinePack", "Archive installation failed: pack=${id.value}", failure)
        if (promoted && !imported) {
            val cleared = withContext(NonCancellable + Dispatchers.IO) {
                runCatching { installer.abortInstallation(id) }.isSuccess
            }
            if (!cleared) return@withPackLock PackOperationResult.STORAGE_ERROR
            return@withPackLock PackOperationResult.IMPORT_ERROR
        }
        if (promoted) return@withPackLock PackOperationResult.STORAGE_ERROR
        if (failure is IllegalArgumentException || failure is SecurityException ||
            failure is java.util.zip.ZipException) PackOperationResult.INVALID_PACK
        else PackOperationResult.STORAGE_ERROR
    }
}


internal fun PackManager.newTemporaryArchive(id: PackId): File {
    check(cacheRoot.isDirectory || cacheRoot.mkdirs())
    check(!java.nio.file.Files.isSymbolicLink(cacheRoot.toPath()))
    return File(cacheRoot, ".${id.value}-${UUID.randomUUID()}.pack.part")
}
