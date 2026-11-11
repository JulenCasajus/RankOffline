package org.rankoffline.app

import androidx.room.withTransaction
import java.util.concurrent.ConcurrentHashMap

/** A batch owns its selected identities and buffers only Room mutations, never image bytes. */
internal class CoverBatchContext(
    val packId: PackId,
    val planned: Set<CatalogEntryKey>,
    val records: Map<CatalogEntryKey, CoverRecord>,
    val fallbackKeys: Map<CatalogEntryKey, List<CatalogEntryKey>>
) {
    data class Update(val record: CoverRecord, val path: String?, val state: CoverResolutionState)
    val updates = ConcurrentHashMap<CatalogEntryKey, Update>()

    fun stage(record: CoverRecord, path: String?, state: CoverResolutionState) {
        require(record.key.packId == packId)
        updates[record.key] = Update(record, path, state)
    }
}

internal suspend fun CoverRepository.beginCoverBatch(packId: PackId, keys: List<CatalogEntryKey>): CoverBatchContext {
    // Resolve all Room-dependent cover references before downloading. An unrelated
    // catalog import can then hold Room without stopping image HTTP or progress.
    val groups = keys.filter { it.kind == CatalogEntryKind.GROUP }
    val fallback = batchFallbackKeys(groups)
    val allKeys = (keys + fallback.values.flatten()).distinct()
    val batch = CoverBatchContext(packId, allKeys.toSet(), records.loadMany(allKeys), fallback)
    check(activeBatches.putIfAbsent(packId, batch) == null) { "Cover batch already active" }
    return batch
}

internal fun CoverRepository.endCoverBatch(batch: CoverBatchContext) {
    activeBatches.remove(batch.packId, batch)
}

/** One short Room transaction after HTTP completes. A crash leaves validated files on disk;
 * the next attempt discovers their deterministic paths and reconciles Room without HTTP. */
internal suspend fun CoverRepository.flushCoverBatch(
    batch: CoverBatchContext, onWaiting: () -> Unit = {}
): Boolean =
    PackCoverWriteLocks.withPackLock(batch.packId) {
        var complete = true
        withCoverCommitTurn(onWaiting) {
            database.withTransaction {
                for (update in batch.updates.values) {
                    val fileValid = update.path == null || validLocal(update.record.key,
                        update.record.remoteRef, update.path)
                    if (!fileValid || !records.updateIfCurrent(update.record, update.path, update.state)) {
                        complete = false
                    }
                }
            }
            complete
        }
    }
