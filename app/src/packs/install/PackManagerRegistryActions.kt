package org.rankoffline.app

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun PackManager.descriptor(id: PackId): PackDownloadDescriptor? = sources.descriptor(id)


fun PackManager.packDefinition(id: PackId): PackDefinition? = registry.definition(id)


fun PackManager.installedDirectory(id: PackId): File? = installer.installedDirectory(id)


/** Cheap discovery only: no cover walking, size calculations or Room queries. */
suspend fun PackManager.refresh() = refreshMutex.withLock {
    withContext(Dispatchers.IO) {
        val available = sources.descriptors.value
        val previousStates = _states.value
        val installed = registry.refresh(available.mapValues { it.value.archiveBytes })
        val changed = registry.changedPacks.value
        val discoveredIds = discoverIds()
        val ids = available.keys + installed.keys + runtime.keys + discoveredIds
        val ready = installed.mapNotNull { (id, definition) ->
            val knownReady = runtime[id] == PackRuntimeStatus.READY && id !in changed
            val directory = installer.installedDirectory(id)
            val current = knownReady || (directory != null &&
                !installer.hasPendingInstallation(id) && !installer.hasPendingDeletion(id) &&
                isCompatiblePackVersion(definition.manifest.minRankOfflineVersion, currentAppVersion) &&
                synchronizer.isCurrentFast(definition.manifest, directory))
            id.takeIf { current }
        }.toSet()
        ready.forEach { runtime[it] = PackRuntimeStatus.READY }
        val next = ids.associateWith { id ->
            val definition = installed[id]
            val summary = summaries.read(id)?.takeIf { cached ->
                definition != null && cached.matches(installer.packDirectory(id))
            }
            val status = if (id in ready) PackRuntimeStatus.READY
                else runtime[id]?.takeUnless { it == PackRuntimeStatus.READY }
                    ?: if (definition != null) PackRuntimeStatus.FILES_INSTALLED
                else if (id in discoveredIds) PackRuntimeStatus.ERROR
                else PackRuntimeStatus.NOT_INSTALLED
            val pending = definition == null && id in discoveredIds
            PackState(
                packId = id,
                // Keep the last validated identity while files are being removed/refreshed.
                // This is process UI state, not a second manifest database.
                manifest = definition?.manifest ?: available[id]?.manifest ?: previousStates[id]?.manifest,
                sourceAvailable = id in available,
                installed = definition != null,
                status = if (definition == null && status == PackRuntimeStatus.READY)
                    PackRuntimeStatus.NOT_INSTALLED else status,
                archiveDownloadBytes = available[id]?.archiveBytes,
                storage = PackStorageSnapshot(
                    installedBytes = summary?.installedBytes ?: 0L,
                    coverBytes = summary?.coverBytes ?: 0L,
                    current = summary?.installedStatsValid == true &&
                        summary.matchesStorage(installer.packDirectory(id))
                ),
                progress = tracker.progress.value[id],
                hasPendingFiles = pending,
                availableCoverCount = summary?.availableCoverCount?.takeIf {
                    !summary.isDirty(PackSummaryField.AVAILABLE_COVER_COUNT)
                }
            )
        }
        synchronized(stateLock) { _states.value = next }
    }
}
