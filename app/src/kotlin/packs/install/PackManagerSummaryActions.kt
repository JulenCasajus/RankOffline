package org.rankoffline.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Refresh only requested dirty fields. A storage scan calculates both byte values in
 * one pass; counts use independent Room queries. Never invoked by cold-start refresh().
 */
suspend fun PackManager.refreshSummary(
    packId: PackId,
    requested: Set<PackSummaryField> = PackSummaryField.entries.toSet()
): LocalPackSummary? = packLock(packId).withLock {
    withContext(Dispatchers.IO) {
        PackCoverWriteLocks.withPackLock(packId) {
            val directory = installer.installedDirectory(packId) ?: return@withPackLock null
            if (requested.isEmpty()) return@withPackLock summaries.read(packId)
            val previous = summaries.read(packId)
            // External edits can invalidate the cache even if no repository callback ran.
            if (previous != null && !previous.matches(directory)) {
                summaries.markAllDirty(packId)
            } else if (previous != null && !previous.matchesStorage(directory)) {
                summaries.markDirty(packId, PackSummaryField.INSTALLED_BYTES,
                    PackSummaryField.COVER_BYTES, PackSummaryField.AVAILABLE_COVER_COUNT)
            }
            var current = summaries.read(packId) ?: LocalPackSummary()
            val storageFields = setOf(PackSummaryField.INSTALLED_BYTES, PackSummaryField.COVER_BYTES)
            if (requested.any { it in storageFields && current.isDirty(it) }) {
                val (installed, covers) = measurePackBytes(directory)
                summaries.recordStorage(packId, directory, installed, covers)
                current = requireNotNull(summaries.read(packId))
                synchronized(stateLock) {
                    _states.value[packId]?.let { state ->
                        _states.value = _states.value + (packId to state.copy(
                            storage = PackStorageSnapshot(installed, covers, current = true)))
                    }
                }
            }
            metrics?.let { provider ->
                if (PackSummaryField.ITEM_COUNT in requested && current.isDirty(PackSummaryField.ITEM_COUNT)) {
                    summaries.recordItemCount(packId, provider.itemCount(packId))
                }
                if (PackSummaryField.GROUP_COUNT in requested && current.isDirty(PackSummaryField.GROUP_COUNT)) {
                    summaries.recordGroupCount(packId, provider.groupCount(packId))
                }
                if (PackSummaryField.AVAILABLE_COVER_COUNT in requested &&
                    current.isDirty(PackSummaryField.AVAILABLE_COVER_COUNT)) {
                    summaries.recordAvailableCovers(packId, provider.availableCoverCount(packId))
                }
                if (PackSummaryField.MISSING_COVER_COUNT in requested &&
                    current.isDirty(PackSummaryField.MISSING_COVER_COUNT)) {
                    summaries.recordMissingCovers(packId, provider.missingCoverCount(packId))
                }
            }
            summaries.read(packId).also { refreshed ->
                synchronized(stateLock) {
                    _states.value[packId]?.let { state ->
                        _states.value = _states.value + (packId to state.copy(
                            availableCoverCount = refreshed?.availableCoverCount?.takeIf {
                                !refreshed.isDirty(PackSummaryField.AVAILABLE_COVER_COUNT)
                            }))
                    }
                }
            }
        }
    }
}


/** Fast cached metadata; dirty bits always accompany potentially stale figures. */
fun PackManager.cachedSummary(packId: PackId): LocalPackSummary? = summaries.read(packId)?.takeIf {
    it.matches(installer.packDirectory(packId))
}


/** Call before ANY cover change. Already-dirty bits are never rewritten. */
fun PackManager.invalidateCoverSummary(packId: PackId, fields: Set<PackSummaryField>) {
    summaries.markDirty(packId, *fields.toTypedArray())
    if (PackSummaryField.INSTALLED_BYTES in fields || PackSummaryField.COVER_BYTES in fields) {
        synchronized(stateLock) {
            _states.value[packId]?.let { state ->
                _states.value = _states.value + (packId to state.copy(
                    storage = state.storage.copy(current = false)))
            }
        }
    }
}
