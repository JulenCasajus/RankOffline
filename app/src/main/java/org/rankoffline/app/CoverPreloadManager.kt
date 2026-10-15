package org.rankoffline.app

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

/** Foreground-only, explicitly requested preload for individually rated items. */
class CoverPreloadManager(
    private val animeRepository: AnimeRepository,
    private val animeCoverRepository: CoverRepository,
    private val genericCoverRepository: GenericMediaCoverRepository
) {
    private val _progress = MutableStateFlow(CoverPreloadProgress())
    val progress: StateFlow<CoverPreloadProgress> = _progress.asStateFlow()

    suspend fun preload(mediaType: MediaType) = withContext(Dispatchers.IO) {
        try {
            val plan = buildPlan(mediaType)
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
        } catch (_: Exception) {
            _progress.value = _progress.value.copy(status = CoverPreloadStatus.FAILED)
        }
    }

    fun resetProgress() {
        _progress.value = CoverPreloadProgress()
    }

    private suspend fun buildPlan(mediaType: MediaType): RatedCoverPlan {
        val candidates = when (mediaType) {
            MediaType.ANIME -> animeRepository.ratedCoverAnime().map { RatedCoverCandidate.AnimeItem(it) }
            else -> genericCoverRepository.ratedCoverItems(mediaType).map { RatedCoverCandidate.GenericItem(it) }
        }
        val pending = mutableListOf<RatedCoverCandidate>()
        var skipped = 0
        candidates.forEach { candidate ->
            val localPath = candidate.localCoverPath
            if (!localPath.isNullOrBlank() && File(localPath).isFile) {
                skipped++
            } else if (!candidate.hasResolvableRemoteCover()) {
                skipped++
            } else {
                if (candidate is RatedCoverCandidate.AnimeItem && !localPath.isNullOrBlank()) {
                    animeCoverRepository.invalidateLocalCover(candidate.anime, localPath)
                }
                pending += candidate
            }
        }
        return RatedCoverPlan(candidates.size, skipped, pending)
    }

    private suspend fun RatedCoverCandidate.download(): CoverDownloadResult = when (this) {
        is RatedCoverCandidate.AnimeItem ->
            animeCoverRepository.downloadPreselectedCover(anime, anime.remoteCoverUrl)
        is RatedCoverCandidate.GenericItem -> genericCoverRepository.downloadCover(item.id)
    }

    private fun RatedCoverCandidate.hasResolvableRemoteCover(): Boolean = when (this) {
        is RatedCoverCandidate.AnimeItem -> !anime.remoteCoverUrl.isNullOrBlank()
        is RatedCoverCandidate.GenericItem -> genericCoverRepository.resolvedRemoteUrl(item) != null
    }

    private sealed interface RatedCoverCandidate {
        val localCoverPath: String?

        data class AnimeItem(val anime: Anime) : RatedCoverCandidate {
            override val localCoverPath get() = anime.localCoverPath
        }

        data class GenericItem(val item: MediaItem) : RatedCoverCandidate {
            override val localCoverPath get() = item.localCoverPath
        }
    }

    private data class RatedCoverPlan(
        val total: Int,
        val skipped: Int,
        val pending: List<RatedCoverCandidate>
    )

    private companion object { const val WORKER_COUNT = 2 }
}
