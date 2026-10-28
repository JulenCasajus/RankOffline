package org.rankoffline.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal enum class PackWorkAction { INSTALL, RECOVER, INITIAL_COVERS }

internal enum class PackWorkDisposition { SUCCESS, RETRYABLE, PERMANENT_FAILURE }

internal fun CategoryPackDownloadResult.workDisposition(): PackWorkDisposition = when (this) {
    CategoryPackDownloadResult.INSTALLED -> PackWorkDisposition.SUCCESS
    CategoryPackDownloadResult.NETWORK_ERROR,
    CategoryPackDownloadResult.RUNTIME_ERROR -> PackWorkDisposition.RETRYABLE
    CategoryPackDownloadResult.UNAVAILABLE,
    CategoryPackDownloadResult.INVALID_PACK -> PackWorkDisposition.PERMANENT_FAILURE
}

internal class PackBackgroundWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = coroutineScope {
        val mediaType = inputData.getString(KEY_MEDIA_TYPE)
            ?.let { persisted ->
                MediaType.entries.firstOrNull {
                    it.persistedValue == persisted || it.toPackId().value == persisted
                }
            }
            ?: return@coroutineScope Result.failure()
        val action = inputData.getString(KEY_ACTION)
            ?.let { runCatching { PackWorkAction.valueOf(it) }.getOrNull() }
            ?: PackWorkAction.INSTALL
        val services = RankOfflineServices.get(applicationContext)
        var progressJob: kotlinx.coroutines.Job? = null
        try {
            setForeground(createForegroundInfo(mediaType))
            progressJob = launch {
                services.categoryPackManager.states.collectLatest { states ->
                    states[mediaType]?.installProgress?.let { progress ->
                        setProgress(
                            Data.Builder()
                                .putString("phase", progress.phase.name)
                                .putLong(
                                    "completed",
                                    (progress.fraction * progress.totalInstalledBytes).toLong()
                                )
                                .putLong("total", progress.totalInstalledBytes)
                                .build()
                        )
                        setForeground(createForegroundInfo(mediaType, progress))
                    }
                }
            }
            val outcome = when (action) {
                PackWorkAction.INSTALL -> services.installCoordinator.install(mediaType)
                    .workDisposition()
                PackWorkAction.RECOVER -> when (services.installCoordinator.recover(mediaType)) {
                    CatalogAvailability.AVAILABLE -> PackWorkDisposition.SUCCESS
                    CatalogAvailability.ERROR -> PackWorkDisposition.RETRYABLE
                    CatalogAvailability.LOADING -> PackWorkDisposition.RETRYABLE
                    CatalogAvailability.UNAVAILABLE -> PackWorkDisposition.PERMANENT_FAILURE
                }
                PackWorkAction.INITIAL_COVERS -> if (
                    services.installCoordinator.resumeInitialCoversIfNeeded(mediaType)
                ) PackWorkDisposition.SUCCESS else PackWorkDisposition.RETRYABLE
            }
            if (outcome == PackWorkDisposition.SUCCESS && action == PackWorkAction.RECOVER &&
                services.categoryPackManager.initialCoverAcquisition(mediaType)?.status ==
                InitialCoverAcquisitionStatus.PENDING
            ) {
                enqueue(applicationContext, mediaType, PackWorkAction.INITIAL_COVERS)
            }
            when (outcome) {
                PackWorkDisposition.SUCCESS -> Result.success()
                PackWorkDisposition.PERMANENT_FAILURE -> Result.failure(failureData("permanent"))
                PackWorkDisposition.RETRYABLE -> if (runAttemptCount + 1 < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure(failureData("retry_exhausted"))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            android.util.Log.e(
                "RankOfflinePack",
                "pack=${mediaType.toPackId().value} action=$action attempt=$runAttemptCount " +
                    "workerFailed=${exception.javaClass.simpleName}: ${exception.message}",
                exception
            )
            if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry()
            else Result.failure(failureData("exception"))
        } finally {
            withContext(NonCancellable) {
                progressJob?.cancelAndJoin()
                runCatching { services.categoryPackManager.refresh() }
                    .onFailure { exception ->
                        android.util.Log.w(
                            "RankOfflinePack",
                            "pack=${mediaType.toPackId().value} action=$action " +
                                "workerCleanup=${exception.javaClass.simpleName}: ${exception.message}",
                            exception
                        )
                    }
            }
        }
    }

    private fun failureData(reason: String): Data = Data.Builder()
        .putString(KEY_FAILURE_REASON, reason)
        .putInt(KEY_ATTEMPTS, runAttemptCount + 1)
        .build()

    private fun createForegroundInfo(
        mediaType: MediaType,
        progress: CategoryPackInstallProgress? = null
    ): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.pack_background_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val (maximum, completed, indeterminate) = if (
            progress?.phase == CategoryPackInstallPhase.DOWNLOADING &&
                progress.totalDownloadBytes > 0L
        ) {
            Triple(1_000, ((progress.fraction * 1_000).toInt()).coerceIn(0, 1_000), false)
        } else {
            Triple(0, 0, true)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.pack_background_title))
            .setContentText(
                progress?.phase?.let { applicationContext.getString(it.notificationTextResource()) }
                    ?: applicationContext.getString(mediaType.notificationNameResource())
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(maximum, completed, indeterminate)
            .build()
        val notificationId = mediaType.ordinal + NOTIFICATION_ID_BASE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        private const val KEY_MEDIA_TYPE = "media_type"
        private const val KEY_ACTION = "action"
        private const val CHANNEL_ID = "rankoffline_pack_operations"
        private const val NOTIFICATION_ID_BASE = 4_200
        private const val KEY_FAILURE_REASON = "failure_reason"
        private const val KEY_ATTEMPTS = "attempts"
        internal const val MAX_ATTEMPTS = 3

        fun enqueue(context: Context, mediaType: MediaType, action: PackWorkAction) {
            val request = request(mediaType, action)
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(mediaType, action),
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        internal fun request(mediaType: MediaType, action: PackWorkAction) =
            OneTimeWorkRequestBuilder<PackBackgroundWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_MEDIA_TYPE, mediaType.persistedValue)
                        .putString(KEY_ACTION, action.name)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (action != PackWorkAction.RECOVER) NetworkType.CONNECTED
                            else NetworkType.NOT_REQUIRED
                        )
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.SECONDS
                )
                .addTag(tag(mediaType))
                .addTag(actionTag(action))
                .build()

        fun cancel(context: Context, mediaType: MediaType) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(mediaType, PackWorkAction.INSTALL))
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(mediaType, PackWorkAction.RECOVER))
            WorkManager.getInstance(context).cancelUniqueWork(
                uniqueName(mediaType, PackWorkAction.INITIAL_COVERS)
            )
        }

        internal fun uniqueName(mediaType: MediaType, action: PackWorkAction) = when (action) {
            PackWorkAction.INITIAL_COVERS -> "pack-covers-${mediaType.toPackId().value}"
            else -> "pack-operation-${mediaType.toPackId().value}"
        }
        private fun tag(mediaType: MediaType) = "pack-${mediaType.toPackId().value}"
        private fun actionTag(action: PackWorkAction) = "pack-action-${action.name}"

        suspend fun reconcilePersistentWork(
            context: Context,
            manager: CategoryPackManager
        ) = withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            MediaType.entries.forEach { mediaType ->
                val operation = workManager.getWorkInfosForUniqueWork(
                    uniqueName(mediaType, PackWorkAction.INSTALL)
                ).get().lastOrNull { it.state.isActive }
                if (operation != null) {
                    val action = PackWorkAction.entries.firstOrNull { action ->
                        actionTag(action) in operation.tags
                    } ?: if (manager.installedDirectory(mediaType) == null) {
                        PackWorkAction.INSTALL
                    } else {
                        PackWorkAction.RECOVER
                    }
                    if (action == PackWorkAction.INSTALL) manager.markInstallQueued(mediaType)
                    else manager.markRecoveryQueued(mediaType)
                }
                val covers = workManager.getWorkInfosForUniqueWork(
                    uniqueName(mediaType, PackWorkAction.INITIAL_COVERS)
                ).get().lastOrNull { it.state.isActive }
                if (covers != null) manager.markInitialCoversQueued(mediaType)
            }
        }

        private val WorkInfo.State.isActive: Boolean
            get() = this == WorkInfo.State.ENQUEUED || this == WorkInfo.State.RUNNING ||
                this == WorkInfo.State.BLOCKED
    }
}

private fun CategoryPackInstallPhase.notificationTextResource(): Int = when (this) {
    CategoryPackInstallPhase.WAITING_DOWNLOAD -> R.string.category_phase_waiting_download
    CategoryPackInstallPhase.DOWNLOADING -> R.string.pack_background_downloading
    CategoryPackInstallPhase.VERIFYING -> R.string.category_phase_verifying
    CategoryPackInstallPhase.INSTALLING -> R.string.category_phase_installing
    CategoryPackInstallPhase.WAITING_IMPORT -> R.string.category_phase_waiting_import
    CategoryPackInstallPhase.IMPORTING_CATALOG -> R.string.category_phase_importing_catalog
    CategoryPackInstallPhase.IMPORTING_GROUPS -> R.string.category_phase_importing_groups
    CategoryPackInstallPhase.WAITING_COVERS -> R.string.category_phase_waiting_covers
    CategoryPackInstallPhase.DOWNLOADING_COVERS -> R.string.covers_downloading_rated
    CategoryPackInstallPhase.FINALIZING -> R.string.category_phase_finalizing
}

private fun MediaType.notificationNameResource(): Int = when (this) {
    MediaType.ANIME -> R.string.nav_anime
    MediaType.MANGA -> R.string.media_manga
    MediaType.SERIES -> R.string.media_series
    MediaType.MOVIE -> R.string.media_movies
    MediaType.BOOK -> R.string.media_books
}
