package org.rankoffline.app

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Batch cover downloads (initial selection or explicit Top-N actions). Storage, image origins and network
 * resolution belong to CoverRepository; this class only schedules requests.
 *
 * Pass explicit entry keys to downloadSelected() when the list contains groups or a mix of entries.
 * No grouping, season or provider rules are applied here.
 */
class CoverDownloadsManager internal constructor(
    private val repository: CoverRepository,
    private val scheduler: PackOperationScheduler,
    private val onBatchFinished: (PackId, CoverDownloadProgress) -> Unit = { _, _ -> }
) {
    constructor(repository: CoverRepository,
                onBatchFinished: (PackId, CoverDownloadProgress) -> Unit = { _, _ -> }) :
        this(repository, PackOperationScheduler(), onBatchFinished)
    private val operationLocks = ConcurrentHashMap<PackId, Mutex>()
    private val progressLock = Any()
    private val _progress = MutableStateFlow(CoverDownloadProgress())
    val progress: StateFlow<CoverDownloadProgress> = _progress.asStateFlow()

    private val _progressByPack = MutableStateFlow<Map<PackId, CoverDownloadProgress>>(emptyMap())
    val progressByPack: StateFlow<Map<PackId, CoverDownloadProgress>> = _progressByPack.asStateFlow()

    /**
     * Initial batch passes manifest.defaults.mergeEnabled and reuse=false.
     * User-requested batches can additionally reuse group covers when viewing separate items.
     */
    suspend fun downloadTop(
        packId: PackId,
        limit: Int,
        mergeEnabled: Boolean,
        useGroupCoversForItems: Boolean = false,
        onProgress: (CoverDownloadProgress) -> Unit = {}
    ): CoverDownloadProgress = execute(packId, onProgress) {
        require(limit in 1..MAX_ENTRIES) { "Invalid download limit" }
        publish(CoverDownloadProgress(packId, CoverDownloadStatus.PREPARING_COVER_LIST), onProgress)
        repository.topPopularVisibleCoverKeys(packId, limit, mergeEnabled, useGroupCoversForItems)
    }

    /** Downloads covers for the requested entries; groups can have their own independent covers. */
    suspend fun downloadSelected(
        packId: PackId,
        entries: List<CatalogEntryKey>,
        onProgress: (CoverDownloadProgress) -> Unit = {}
    ): CoverDownloadProgress = execute(packId, onProgress) { entries }

    /** Clears only progress, never files, download states or in-flight operations. */
    fun resetProgress(packId: PackId? = null) = synchronized(progressLock) {
        if (packId == null) {
            // Do not overwrite progress from an operation that is still running.
            _progressByPack.value = _progressByPack.value.filterValues { it.isActive }
            if (!_progress.value.isActive) _progress.value = CoverDownloadProgress()
        } else {
            val current = _progressByPack.value[packId]
            if (current?.isActive == true) return@synchronized
            _progressByPack.value = _progressByPack.value - packId
            if (_progress.value.packId == packId && !_progress.value.isActive) {
                _progress.value = CoverDownloadProgress()
            }
        }
    }

    private suspend fun execute(
        packId: PackId,
        onProgress: (CoverDownloadProgress) -> Unit,
        select: suspend () -> List<CatalogEntryKey>
    ): CoverDownloadProgress = withContext(Dispatchers.IO) {
        operationLocks.computeIfAbsent(packId) { Mutex() }.withLock {
            try {
                val raw = select()
                require(raw.size <= MAX_ENTRIES) { "Too many covers requested" }
                require(raw.all { it.packId == packId }) { "Entries belong to different packs" }
                val entries = raw.distinct()
                val outcomes = LinkedHashMap<CatalogEntryKey, CoverDownloadResult>(entries.size)
                val exhausted = HashSet<CatalogEntryKey>()
                publish(coverProgressSnapshot(packId, CoverDownloadStatus.DOWNLOADING_COVERS,
                    entries.size, outcomes, exhausted), onProgress)

                for (pass in 0 until DOWNLOAD_PASSES) {
                    val pending = if (pass == 0) entries
                        else entries.filter { outcomes[it].isRetryableCoverResult() }
                    if (pending.isEmpty()) break
                    val status = if (pass == 0) CoverDownloadStatus.DOWNLOADING_COVERS
                        else CoverDownloadStatus.RETRYING_COVERS
                    publish(coverProgressSnapshot(packId, status, entries.size, outcomes, exhausted), onProgress)
                    for (batch in pending.chunked(BATCH_SIZES[pass])) {
                        downloadBatch(batch).forEach { (key, result) -> outcomes[key] = result }
                        if (pass == DOWNLOAD_PASSES - 1) {
                            batch.filter { outcomes[it].isRetryableCoverResult() }.forEach(exhausted::add)
                        }
                        publish(coverProgressSnapshot(packId, status, entries.size, outcomes, exhausted), onProgress)
                    }
                }
                coverProgressSnapshot(packId, CoverDownloadStatus.COMPLETED,
                    entries.size, outcomes, exhausted)
                    .also {
                        publish(it, onProgress)
                        runCatching { onBatchFinished(packId, it) }
                    }
            } catch (cancelled: CancellationException) {
                publish(terminal(packId, CoverDownloadStatus.CANCELLED), onProgress)
                throw cancelled
            } catch (exception: Exception) {
                Log.w(TAG, "pack=${packId.value} operation=batch-download failed=${exception.javaClass.simpleName}", exception)
                terminal(packId, CoverDownloadStatus.FAILED).also {
                    publish(it, onProgress)
                    runCatching { onBatchFinished(packId, it) }
                }
            }
        }
    }

    private suspend fun downloadBatch(
        keys: List<CatalogEntryKey>
    ): List<Pair<CatalogEntryKey, CoverDownloadResult>> = coroutineScope {
        keys.map { key ->
            async {
                val result = try {
                    scheduler.withCoverTransfer { repository.downloadCover(key) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Log.w(TAG, "pack=${key.packId.value} entry=${key.id} cover-batch-download failed", exception)
                    CoverDownloadResult.TemporaryError
                }
                key to result
            }
        }.awaitAll()
    }

    private fun terminal(packId: PackId, status: CoverDownloadStatus): CoverDownloadProgress =
        (_progressByPack.value[packId] ?: CoverDownloadProgress(packId = packId)).copy(status = status)

    private fun publish(value: CoverDownloadProgress, callback: (CoverDownloadProgress) -> Unit) {
        synchronized(progressLock) {
            _progressByPack.value = _progressByPack.value + (requireNotNull(value.packId) to value)
            _progress.value = value
        }
        callback(value)
    }

    private companion object {
        // Per-pass request batch widths. The scheduler imposes a separate global network cap.
        const val DOWNLOAD_PASSES = 3
        val BATCH_SIZES = listOf(3, 2, 1).also {
            require(it.size == DOWNLOAD_PASSES && it.all { size -> size > 0 })
        }
        const val MAX_ENTRIES = 5_000
        const val TAG = "RankOfflineCover"
    }
}

internal fun CoverDownloadResult?.isRetryableCoverResult(): Boolean =
    this == CoverDownloadResult.AlreadyInProgress ||
        this == CoverDownloadResult.NetworkError ||
        this == CoverDownloadResult.TemporaryError

internal fun coverProgressSnapshot(
    packId: PackId,
    status: CoverDownloadStatus,
    target: Int,
    outcomes: Map<CatalogEntryKey, CoverDownloadResult>,
    exhausted: Set<CatalogEntryKey>
): CoverDownloadProgress {
    // Only the third unsuccessful attempt counts as an error. Each key is counted once.
    val finalErrors = exhausted.count { outcomes[it].isRetryableCoverResult() }
    return CoverDownloadProgress(
        packId = packId,
        status = status,
        target = target,
        processed = outcomes.values.count { !it.isRetryableCoverResult() } + finalErrors,
        existing = outcomes.values.count { it == CoverDownloadResult.AlreadyAvailable },
        downloaded = outcomes.values.count { it == CoverDownloadResult.Downloaded },
        unavailable = outcomes.values.count { it == CoverDownloadResult.NotFound },
        errors = finalErrors
    )
}
