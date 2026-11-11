package org.rankoffline.app

import android.util.Log
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
class CoverDownloadsManager(
    private val repository: CoverRepository,
    private val onBatchFinished: (PackId, CoverDownloadProgress) -> Unit = { _, _ -> }
) {
    private val operationLocks = ConcurrentHashMap<PackId, Mutex>()
    private val progressLock = Any()
    private val visibleProgress = mutableSetOf<PackId>()
    private val _progress = MutableStateFlow(CoverDownloadProgress())
    val progress: StateFlow<CoverDownloadProgress> = _progress.asStateFlow()

    private val _progressByPack = MutableStateFlow<Map<PackId, CoverDownloadProgress>>(emptyMap())
    val progressByPack: StateFlow<Map<PackId, CoverDownloadProgress>> = _progressByPack.asStateFlow()

    /**
     * Initial batch passes manifest.defaults.mergeEnabled and reuse=false.
     * Merge determines whether visible identities are groups or individual items.
     */
    suspend fun downloadTop(
        packId: PackId,
        limit: Int,
        mergeEnabled: Boolean,
        onProgress: (CoverDownloadProgress) -> Unit = {}
    ): CoverDownloadProgress = execute(packId, onProgress) {
        require(limit in 1..MAX_ENTRIES) { "Invalid download limit" }
        publish(CoverDownloadProgress(packId, CoverDownloadStatus.PREPARING_COVER_LIST), onProgress)
        repository.topPopularVisibleCoverKeys(packId, limit, mergeEnabled)
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

    /** A finished batch remains visible only until Covers is left. Active work stays observable. */
    fun setProgressVisible(packId: PackId, visible: Boolean) = synchronized(progressLock) {
        if (visible) visibleProgress += packId else {
            visibleProgress -= packId
            resetProgress(packId)
        }
    }

    private suspend fun execute(
        packId: PackId,
        onProgress: (CoverDownloadProgress) -> Unit,
        select: suspend () -> List<CatalogEntryKey>
    ): CoverDownloadProgress = withContext(Dispatchers.IO) {
        publish(CoverDownloadProgress(packId, CoverDownloadStatus.WAITING_FOR_COVERS), onProgress)
        try {
            operationLocks.computeIfAbsent(packId) { Mutex() }.withLock {
                val startedAt = SystemClock.elapsedRealtime()
                val raw = select()
                require(raw.size <= MAX_ENTRIES) { "Too many covers requested" }
                require(raw.all { it.packId == packId }) { "Entries belong to different packs" }
                val entries = raw.distinct()
                val batch = repository.beginCoverBatch(packId, entries)
                Log.i(TAG, "pack=${packId.value} phase=cover-prepared target=${entries.size} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                var flushAttempted = false
                var flushSucceeded = false
                try {
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
                        downloadPending(pending, batch) { key, result ->
                            // The batch defers Room writes. Show a confirmed absence as soon
                            // as this entry finishes, even while another catalog holds Room.
                            if (result == CoverDownloadResult.NotFound) {
                                repository.states[key]?.value = CoverDownloadState.NOT_FOUND
                            }
                            if (outcomes.isEmpty()) Log.i(TAG,
                                "pack=${packId.value} phase=first-cover-result " +
                                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} result=$result")
                            outcomes[key] = result
                            if (pass == DOWNLOAD_PASSES - 1 && result.isRetryableCoverResult()) {
                                exhausted += key
                            }
                            publish(coverProgressSnapshot(packId, status,
                                entries.size, outcomes, exhausted), onProgress)
                        }
                    }
                    flushAttempted = true
                    check(withContext(NonCancellable) { repository.flushCoverBatch(batch) {
                        publish(coverProgressSnapshot(packId, CoverDownloadStatus.WAITING_TO_SAVE,
                            entries.size, outcomes, exhausted), onProgress)
                    } }) {
                        "Cover batch state changed during download"
                    }
                    flushSucceeded = true
                    Log.i(TAG, "pack=${packId.value} phase=cover-committed " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} updates=${batch.updates.size}")
                    coverProgressSnapshot(packId, CoverDownloadStatus.COMPLETED,
                        entries.size, outcomes, exhausted)
                        .also {
                            publish(it, onProgress)
                            runCatching { onBatchFinished(packId, it) }
                        }
                } finally {
                    if (!flushAttempted && batch.updates.isNotEmpty()) {
                        // A cancelled Worker must retain images already promoted. The network
                        // has stopped; only their small Room state transaction remains.
                        withContext(NonCancellable) {
                            runCatching { repository.flushCoverBatch(batch) }
                                .onSuccess { flushSucceeded = it }
                                .onFailure { Log.w(TAG, "pack=${packId.value} partial cover flush failed", it) }
                        }
                    }
                    if (!flushSucceeded) entries.forEach { key ->
                        repository.states[key]?.compareAndSet(
                            CoverDownloadState.NOT_FOUND, CoverDownloadState.IDLE)
                    }
                    repository.endCoverBatch(batch)
                }
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

    private suspend fun downloadPending(
        keys: List<CatalogEntryKey>,
        batch: CoverBatchContext,
        completed: (CatalogEntryKey, CoverDownloadResult) -> Unit
    ) = coroutineScope {
        // Room writes are staged until this pass finishes, so an unrelated import
        // cannot occupy every HTTP worker after the first few images.
        val workers = minOf(keys.size, MAX_PENDING_COVERS)
        val next = AtomicInteger()
        val results = kotlinx.coroutines.channels.Channel<Pair<CatalogEntryKey, CoverDownloadResult>>(
            capacity = workers)
        repeat(minOf(workers, keys.size)) {
            launch {
                while (true) {
                    val index = next.getAndIncrement()
                    if (index >= keys.size) break
                    val key = keys[index]
                    val result = try {
                        repository.downloadCover(key, batch)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (exception: Exception) {
                        Log.w(TAG, "pack=${key.packId.value} entry=${key.id} cover-batch-download failed", exception)
                        CoverDownloadResult.TemporaryError
                    }
                    results.send(key to result)
                }
            }
        }
        repeat(keys.size) {
            val (key, result) = results.receive()
            completed(key, result)
        }
        results.close()
    }

    private fun terminal(packId: PackId, status: CoverDownloadStatus): CoverDownloadProgress =
        (_progressByPack.value[packId] ?: CoverDownloadProgress(packId = packId)).copy(status = status)

    private fun publish(value: CoverDownloadProgress, callback: (CoverDownloadProgress) -> Unit) {
        synchronized(progressLock) {
            _progressByPack.value = _progressByPack.value + (requireNotNull(value.packId) to value)
            _progress.value = value
            if (!value.isActive && value.packId !in visibleProgress) resetProgress(value.packId)
        }
        callback(value)
    }

    private companion object {
        const val DOWNLOAD_PASSES = 3
        const val MAX_PENDING_COVERS = 12
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
