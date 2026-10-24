package org.rankoffline.app

import android.util.Log
import java.io.File
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

/** Shared bounded two-worker cover preload for installation and explicit Top-N actions. */
class CoverPreloadManager(
    private val genericCoverRepository: GenericMediaCoverRepository
) {
    private val _progress = MutableStateFlow(CoverPreloadProgress())
    val progress: StateFlow<CoverPreloadProgress> = _progress.asStateFlow()
    private val operationMutex = Mutex()

    suspend fun preloadTop(
        mediaType: MediaType,
        limit: Int,
        oneCoverPerGroup: Boolean,
        onProgress: (CoverPreloadProgress) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val candidates = when (mediaType) {
                else -> genericCoverRepository.topPopularCoverItems(
                    mediaType,
                    limit,
                    oneCoverPerGroup
                )
                    .map { RatedCoverCandidate.GenericItem(it) }
            }
            preloadPlan(mediaType, buildPlan(candidates, limit), onProgress)
        }
    }

    private suspend fun preloadPlan(
        mediaType: MediaType,
        plan: RatedCoverPlan,
        onProgress: (CoverPreloadProgress) -> Unit
    ): CoverPreloadProgress =
        withContext(Dispatchers.IO) {
        try {
            publish(CoverPreloadProgress(
                mediaType = mediaType,
                status = CoverPreloadStatus.DOWNLOADING_COVERS,
                target = plan.total,
                processed = plan.existing + plan.unavailable,
                existing = plan.existing,
                unavailable = plan.unavailable
            ), onProgress)
            var processed = plan.existing + plan.unavailable
            var existing = plan.existing
            var downloaded = 0
            var unavailable = plan.unavailable
            var errors = 0
            plan.pending.chunked(WORKER_COUNT).forEach { batch ->
                val results = coroutineScope {
                    batch.map { candidate -> async { candidate.download() } }.awaitAll()
                }
                results.forEach { result ->
                    when (result) {
                        CoverDownloadResult.Downloaded -> downloaded++
                        CoverDownloadResult.AlreadyAvailable -> existing++
                        CoverDownloadResult.NotFound -> unavailable++
                        CoverDownloadResult.AlreadyInProgress,
                        CoverDownloadResult.NetworkError,
                        CoverDownloadResult.TemporaryError -> errors++
                    }
                    processed++
                }
                publish(CoverPreloadProgress(
                    mediaType = mediaType,
                    status = CoverPreloadStatus.DOWNLOADING_COVERS,
                    target = plan.total,
                    processed = processed,
                    existing = existing,
                    downloaded = downloaded,
                    unavailable = unavailable,
                    errors = errors
                ), onProgress)
            }
            publish(_progress.value.copy(status = CoverPreloadStatus.COMPLETED), onProgress)
        } catch (exception: CancellationException) {
            publish(_progress.value.copy(status = CoverPreloadStatus.CANCELLED), onProgress)
            throw exception
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "operation=preload processed=${_progress.value.processed} target=${plan.total} " +
                    "failed=${exception.javaClass.simpleName}: ${exception.message}",
                exception
            )
            publish(_progress.value.copy(status = CoverPreloadStatus.FAILED), onProgress)
        }
        _progress.value
    }

    fun resetProgress() {
        _progress.value = CoverPreloadProgress()
    }

    private suspend fun buildPlan(
        candidates: List<RatedCoverCandidate>,
        requestedTarget: Int
    ): RatedCoverPlan {
        val pending = mutableListOf<RatedCoverCandidate>()
        var existing = 0
        var unavailable = 0
        candidates.forEach { candidate ->
            val localPath = candidate.localCoverPath
            if (!localPath.isNullOrBlank() && File(localPath).isFile) {
                existing++
            } else if (!candidate.hasResolvableRemoteCover()) {
                unavailable++
            } else {
                pending += candidate
            }
        }
        val missingCandidates = (requestedTarget - candidates.size).coerceAtLeast(0)
        return RatedCoverPlan(
            total = requestedTarget,
            existing = existing,
            unavailable = unavailable + missingCandidates,
            pending = pending
        )
    }

    private fun publish(
        progress: CoverPreloadProgress,
        onProgress: (CoverPreloadProgress) -> Unit
    ) {
        _progress.value = progress
        onProgress(progress)
    }

    private suspend fun RatedCoverCandidate.download(): CoverDownloadResult = when (this) {
        is RatedCoverCandidate.GenericItem -> genericCoverRepository.downloadCover(item.id)
    }

    private fun RatedCoverCandidate.hasResolvableRemoteCover(): Boolean = when (this) {
        is RatedCoverCandidate.GenericItem ->
            genericCoverRepository.resolvedRemoteUrl(item) != null ||
                (item.mediaType == MediaType.ANIME &&
                    (item.id.startsWith("mal:") || item.id.startsWith("anilist:")))
    }

    private sealed interface RatedCoverCandidate {
        val localCoverPath: String?

        data class GenericItem(val item: MediaItem) : RatedCoverCandidate {
            override val localCoverPath get() = item.localCoverPath
        }
    }

    private data class RatedCoverPlan(
        val total: Int,
        val existing: Int,
        val unavailable: Int,
        val pending: List<RatedCoverCandidate>
    )

    private companion object {
        const val WORKER_COUNT = 2
        const val TAG = "RankOfflineCover"
    }
}
