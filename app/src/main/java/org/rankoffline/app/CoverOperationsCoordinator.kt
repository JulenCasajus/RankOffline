package org.rankoffline.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Coordinates cover operations without coupling catalog query invalidation to visual state. */
internal class CoverOperationsCoordinator(
    private val scope: CoroutineScope,
    private val settings: StateFlow<AppSettingsState>,
    private val catalogAvailability: StateFlow<Map<MediaType, CatalogAvailability>>,
    private val settingsRepository: SettingsRepository,
    private val genericCoverRepository: GenericMediaCoverRepository,
    private val genericGroupCoverRepository: GenericMediaGroupCoverRepository,
    private val preloadManager: CoverPreloadManager,
    private val sendMessage: suspend (UserMessage) -> Unit
) {
    val progress: StateFlow<CoverPreloadProgress> = preloadManager.progress
    private var preloadJob: Job? = null
    private val manualDownloadJobs = ConcurrentHashMap<String, Job>()
    private var settingsVisible = false

    fun download(anime: Anime) {
        if (manualDownloadJobs.containsKey(anime.id)) return
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = when {
                    anime.supportsRemoteGroupCover() -> genericGroupCoverRepository.downloadCover(anime.id)
                    else -> genericCoverRepository.downloadCover(anime.id)
                }
                when (result) {
                    CoverDownloadResult.NetworkError -> sendMessage(UserMessage.Text(R.string.message_no_internet))
                    CoverDownloadResult.NotFound -> sendMessage(UserMessage.Text(R.string.message_cover_not_found))
                    CoverDownloadResult.TemporaryError ->
                        sendMessage(UserMessage.Text(R.string.message_cover_download_failed))
                    CoverDownloadResult.Downloaded,
                    CoverDownloadResult.AlreadyAvailable,
                    CoverDownloadResult.AlreadyInProgress -> Unit
                }
            } finally {
                manualDownloadJobs.remove(anime.id, job)
            }
        }
        val previous = manualDownloadJobs.putIfAbsent(anime.id, job)
        if (previous == null) job.start() else job.cancel()
    }

    fun downloadTop(limit: Int) {
        if (preloadJob?.isActive == true || progress.value.isActive) return
        val current = settings.value
        startPreload(
            current.activeMediaType,
            limit,
            current.activeMediaSettings.oneCoverPerGroup
        )
    }

    fun cancelPreload() {
        preloadJob?.cancel()
    }

    suspend fun cancelPreloadAndJoin() {
        preloadJob?.cancelAndJoin()
    }

    suspend fun cancelAllAndJoin() {
        cancelPreloadAndJoin()
        val jobs = manualDownloadJobs.values.toList()
        jobs.forEach(Job::cancel)
        jobs.forEach { it.join() }
        manualDownloadJobs.clear()
    }

    fun settingsVisibilityChanged(visible: Boolean) {
        settingsVisible = visible
        if (!visible) clearTerminalProgress()
    }

    fun delete(anime: Anime, onComplete: (Boolean) -> Unit) {
        scope.launch {
            val deleted = when {
                anime.supportsRemoteGroupCover() -> genericGroupCoverRepository.removeLocalCover(anime.id)
                else -> genericCoverRepository.removeLocalCover(anime.id)
            }
            sendMessage(
                UserMessage.Text(
                    if (deleted) R.string.message_cover_deleted else R.string.message_cover_delete_failed
                )
            )
            onComplete(deleted)
        }
    }

    fun clearDownloaded() {
        scope.launch {
            val mediaType = settings.value.activeMediaType
            cancelAllAndJoin()
            val removed = genericCoverRepository.clearDownloadedCovers(mediaType) +
                genericGroupCoverRepository.clearDownloadedCovers(mediaType)
            preloadManager.resetProgress()
            sendMessage(
                if (removed == 1) UserMessage.Text(R.string.message_cover_deleted)
                else UserMessage.Plural(R.plurals.message_covers_deleted, removed, listOf(removed))
            )
        }
    }

    fun startTopWarmup(mediaTypes: List<MediaType>) {
        val previous = preloadJob
        preloadJob = scope.launch {
            previous?.cancelAndJoin()
            mediaTypes.distinct().forEach { mediaType ->
                if (catalogAvailability.value[mediaType] == CatalogAvailability.AVAILABLE &&
                    mediaType !in settings.value.completedTopCoverWarmups
                ) {
                    val result = preloadManager.preloadTop(
                        mediaType,
                        limit = 100,
                        oneCoverPerGroup = settings.value.mediaSettings[mediaType]?.oneCoverPerGroup == true
                    )
                    if (result.status == CoverPreloadStatus.COMPLETED && result.downloaded + result.existing > 0) {
                        settingsRepository.markTopCoverWarmupCompleted(mediaType)
                    }
                }
            }
            if (!settingsVisible) clearTerminalProgress()
        }
    }

    private fun startPreload(
        mediaType: MediaType,
        limit: Int,
        oneCoverPerGroup: Boolean
    ) {
        val previous = preloadJob
        preloadJob = scope.launch {
            previous?.cancelAndJoin()
            preloadManager.preloadTop(mediaType, limit, oneCoverPerGroup)
            if (!settingsVisible) clearTerminalProgress()
        }
    }

    private fun clearTerminalProgress() {
        if (progress.value.shouldClearAfterLeavingSettings()) preloadManager.resetProgress()
    }
}
