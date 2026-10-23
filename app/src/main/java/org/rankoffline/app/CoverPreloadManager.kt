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
import kotlinx.coroutines.withContext

/** Bounded two-worker cover preload for explicit Top-N cover actions. */
class CoverPreloadManager(
    private val genericCoverRepository: GenericMediaCoverRepository
) {
    private val _progress = MutableStateFlow(CoverPreloadProgress())
    val progress: StateFlow<CoverPreloadProgress> = _progress.asStateFlow()

    suspend fun preload(mediaType: MediaType) = withContext(Dispatchers.IO) {
        preloadPlan(buildRatedPlan(mediaType))
    }

    suspend fun preloadTop(
        mediaType: MediaType,
        limit: Int,
        oneCoverPerGroup: Boolean
    ) = withContext(Dispatchers.IO) {
        val candidates = when (mediaType) {
            else -> genericCoverRepository.topPopularCoverItems(
                mediaType,
                limit,
                oneCoverPerGroup
            )
                .map { RatedCoverCandidate.GenericItem(it) }
        }
        preloadPlan(buildPlan(candidates))
    }

    private suspend fun preloadPlan(
        plan: RatedCoverPlan
    ): CoverPreloadProgress =
        withContext(Dispatchers.IO) {
        try {
            _progress.value = CoverPreloadProgress(
                status = CoverPreloadStatus.DOWNLOADING_COVERS,
                target = plan.total,
                processed = plan.skipped,
                existing = plan.skipped
            )
            var processed = plan.skipped
            var skipped = plan.skipped
            var downloaded = 0
            var errors = 0
            plan.pending.chunked(WORKER_COUNT).forEach { batch ->
                val results = coroutineScope {
                    batch.map { candidate -> async { candidate.download() } }.awaitAll()
                }
                results.forEach { result ->
                    when (result) {
                        CoverDownloadResult.Downloaded -> downloaded++
                        CoverDownloadResult.AlreadyAvailable,
                        CoverDownloadResult.AlreadyInProgress -> skipped++
                        CoverDownloadResult.NetworkError,
                        CoverDownloadResult.NotFound,
                        CoverDownloadResult.TemporaryError -> errors++
                    }
                    processed++
                }
                _progress.value = CoverPreloadProgress(
                    status = CoverPreloadStatus.DOWNLOADING_COVERS,
                    target = plan.total,
                    processed = processed,
                    existing = skipped,
                    downloaded = downloaded,
                    errors = errors
                )
            }
            _progress.value = _progress.value.copy(status = CoverPreloadStatus.COMPLETED)
        } catch (exception: CancellationException) {
            _progress.value = CoverPreloadProgress()
            throw exception
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "operation=preload processed=${_progress.value.processed} target=${plan.total} " +
                    "failed=${exception.javaClass.simpleName}: ${exception.message}",
                exception
            )
            _progress.value = _progress.value.copy(status = CoverPreloadStatus.FAILED)
        }
        _progress.value
    }

    fun resetProgress() {
        _progress.value = CoverPreloadProgress()
    }

    private suspend fun buildRatedPlan(mediaType: MediaType): RatedCoverPlan {
        val candidates = when (mediaType) {
            else -> genericCoverRepository.ratedCoverItems(mediaType).map { RatedCoverCandidate.GenericItem(it) }
        }
        return buildPlan(candidates)
    }

    private suspend fun buildPlan(
        candidates: List<RatedCoverCandidate>
    ): RatedCoverPlan {
        val pending = mutableListOf<RatedCoverCandidate>()
        var skipped = 0
        candidates.forEach { candidate ->
            val localPath = candidate.localCoverPath
            if (!localPath.isNullOrBlank() && File(localPath).isFile) {
                skipped++
            } else if (!candidate.hasResolvableRemoteCover()) {
                skipped++
            } else {
                pending += candidate
            }
        }
        return RatedCoverPlan(candidates.size, skipped, pending)
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
        val skipped: Int,
        val pending: List<RatedCoverCandidate>
    )

    private companion object {
        const val WORKER_COUNT = 2
        const val TAG = "RankOfflineCover"
    }
}
