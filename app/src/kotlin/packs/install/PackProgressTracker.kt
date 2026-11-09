package org.rankoffline.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory progress only. WorkManager is responsible for persistent scheduling. */
internal class PackProgressTracker(
    private val onChange: (PackId, PackInstallProgress?) -> Unit = { _, _ -> }
) {
    private val monitor = Any()
    private val _progress = MutableStateFlow<Map<PackId, PackInstallProgress>>(emptyMap())
    val progress: StateFlow<Map<PackId, PackInstallProgress>> = _progress.asStateFlow()
    private val _aggregate = MutableStateFlow<PackAggregateProgress?>(null)
    val aggregate: StateFlow<PackAggregateProgress?> = _aggregate.asStateFlow()

    fun set(packId: PackId, value: PackInstallProgress) = synchronized(monitor) {
        _progress.value = _progress.value + (packId to value)
        publishAggregate()
        onChange(packId, value)
    }

    fun phase(packId: PackId, phase: PackInstallPhase) = synchronized(monitor) {
        val previous = _progress.value[packId] ?: PackInstallProgress(phase)
        _progress.value = _progress.value + (packId to previous.copy(phase = phase))
        publishAggregate()
        onChange(packId, _progress.value[packId])
    }

    fun downloaded(packId: PackId, downloaded: Long) = synchronized(monitor) {
        val previous = _progress.value[packId] ?: return@synchronized
        _progress.value = _progress.value + (packId to previous.copy(
            downloadedBytes = downloaded.coerceIn(0, previous.totalDownloadBytes)))
        publishAggregate()
        onChange(packId, _progress.value[packId])
    }

    fun covers(packId: PackId, progress: CoverDownloadProgress) = synchronized(monitor) {
        val previous = _progress.value[packId] ?: return@synchronized
        val value = previous.copy(
            coverDownloaded = progress.downloaded,
            coverTarget = progress.target.takeIf { it > 0 } ?: previous.coverTarget,
            coverProcessed = progress.processed, coverNotFound = progress.unavailable,
            coverErrors = progress.errors
        )
        _progress.value = _progress.value + (packId to value)
        onChange(packId, value)
    }

    fun clear(packId: PackId) = synchronized(monitor) {
        _progress.value = _progress.value - packId
        publishAggregate()
        onChange(packId, null)
    }

    private fun publishAggregate() {
        val active = _progress.value.values.filter { it.totalDownloadBytes > 0 }
        _aggregate.value = if (active.isEmpty()) null else PackAggregateProgress(
            downloadedBytes = active.fold(0L) { sum, it -> Math.addExact(sum, it.downloadedBytes) },
            totalDownloadBytes = active.fold(0L) { sum, it -> Math.addExact(sum, it.totalDownloadBytes) },
            transferCount = active.size
        )
    }
}
