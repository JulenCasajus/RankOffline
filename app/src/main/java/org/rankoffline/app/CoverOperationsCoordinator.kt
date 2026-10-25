package org.rankoffline.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.ConcurrentHashMap

/** Coordinates cover operations without coupling catalog query invalidation to visual state. */
internal class CoverOperationsCoordinator(
    private val scope: CoroutineScope,
    private val settings: StateFlow<AppSettingsState>,
    private val genericCoverRepository: GenericMediaCoverRepository,
    private val genericGroupCoverRepository: GenericMediaGroupCoverRepository,
    private val preloadManager: CoverPreloadManager,
    private val sendMessage: suspend (UserMessage) -> Unit
) {
    val progressByMedia: StateFlow<Map<MediaType, CoverPreloadProgress>> =
        preloadManager.progressByMedia
    private val manualPreloadJobs = ConcurrentHashMap<MediaType, Job>()
    private val initialPreloadJobs = ConcurrentHashMap<MediaType, Deferred<CoverPreloadProgress>>()
    private val manualDownloadJobs = ConcurrentHashMap<String, ManualCoverJob>()
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
                manualDownloadJobs.remove(anime.id, ManualCoverJob(anime.mediaType, job))
            }
        }
        val operation = ManualCoverJob(anime.mediaType, job)
        val previous = manualDownloadJobs.putIfAbsent(anime.id, operation)
        if (previous == null) job.start() else job.cancel()
    }

    fun downloadTop(limit: Int) {
        val current = settings.value
        val mediaType = current.activeMediaType
        if (manualPreloadJobs[mediaType]?.isActive == true ||
            initialPreloadJobs[mediaType]?.isActive == true
        ) {
            scope.launch { sendMessage(UserMessage.Text(R.string.covers_operation_busy)) }
            return
        }
        preloadManager.markPreparing(mediaType, limit)
        startPreload(
            mediaType,
            limit,
            current.activeMediaSettings.oneCoverPerGroup
        )
    }

    fun cancelPreload() {
        val mediaType = settings.value.activeMediaType
        manualPreloadJobs[mediaType]?.cancel()
        initialPreloadJobs[mediaType]?.cancel()
    }

    fun cancelInitialPreload(mediaType: MediaType) {
        initialPreloadJobs[mediaType]?.cancel()
    }

    suspend fun preloadInitialTop(
        mediaType: MediaType,
        limit: Int,
        oneCoverPerGroup: Boolean,
        onProgress: (CoverPreloadProgress) -> Unit
    ): CoverPreloadProgress = supervisorScope {
        val task = async {
            preloadManager.preloadTop(
                mediaType,
                limit,
                oneCoverPerGroup,
                onProgress = onProgress
            )
        }
        val previous = initialPreloadJobs.putIfAbsent(mediaType, task)
        if (previous != null) {
            task.cancel()
            return@supervisorScope previous.await()
        }
        try {
            task.await()
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            preloadManager.progressByMedia.value[mediaType]
                ?.copy(status = CoverPreloadStatus.CANCELLED)
                ?: CoverPreloadProgress(
                    mediaType = mediaType,
                    status = CoverPreloadStatus.CANCELLED,
                    target = limit
                )
        } finally {
            initialPreloadJobs.remove(mediaType, task)
        }
    }

    suspend fun cancelPreloadAndJoin(mediaType: MediaType = settings.value.activeMediaType) {
        manualPreloadJobs[mediaType]?.cancelAndJoin()
        initialPreloadJobs[mediaType]?.cancelAndJoin()
    }

    suspend fun cancelAllAndJoin() {
        val manualPreloads = manualPreloadJobs.values.toList()
        manualPreloads.forEach(Job::cancel)
        manualPreloads.forEach { it.join() }
        manualPreloadJobs.clear()
        val initialJobs = initialPreloadJobs.values.toList()
        initialJobs.forEach { it.cancel() }
        initialJobs.forEach { runCatching { it.await() } }
        initialPreloadJobs.clear()
        val jobs = manualDownloadJobs.values.map(ManualCoverJob::job)
        jobs.forEach(Job::cancel)
        jobs.forEach { it.join() }
        manualDownloadJobs.clear()
    }

    fun settingsVisibilityChanged(visible: Boolean) {
        settingsVisible = visible
        if (!visible) clearTerminalProgress(settings.value.activeMediaType)
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
            if (progressByMedia.value[mediaType]?.isActive == true) cancelPreloadAndJoin(mediaType)
            val manualJobs = manualDownloadJobs.values
                .filter { it.mediaType == mediaType }
                .map(ManualCoverJob::job)
            manualJobs.forEach(Job::cancel)
            manualJobs.forEach { it.join() }
            val removed = genericCoverRepository.clearDownloadedCovers(mediaType) +
                genericGroupCoverRepository.clearDownloadedCovers(mediaType)
            genericCoverRepository.clearDownloadedCoverFiles(mediaType)
            preloadManager.resetProgress(mediaType)
            sendMessage(
                if (removed == 1) UserMessage.Text(R.string.message_cover_deleted)
                else UserMessage.Plural(R.plurals.message_covers_deleted, removed, listOf(removed))
            )
        }
    }

    private fun startPreload(
        mediaType: MediaType,
        limit: Int,
        oneCoverPerGroup: Boolean
    ) {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                preloadManager.preloadTop(mediaType, limit, oneCoverPerGroup)
                if (!settingsVisible) clearTerminalProgress(mediaType)
            } finally {
                manualPreloadJobs.remove(mediaType, job)
            }
        }
        val previous = manualPreloadJobs.putIfAbsent(mediaType, job)
        if (previous == null) job.start() else job.cancel()
    }

    private fun clearTerminalProgress(mediaType: MediaType) {
        if (progressByMedia.value[mediaType]?.shouldClearAfterLeavingSettings() == true) {
            preloadManager.resetProgress(mediaType)
        }
    }

    private data class ManualCoverJob(val mediaType: MediaType, val job: Job)
}
