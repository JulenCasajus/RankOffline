package org.rankoffline.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Persistent execution boundary; PackManager performs the actual install/recover operations. */
class PackBackgroundWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val packId = inputData.getLong(PackWorkScheduler.KEY_PACK_ID, 0L)
            .let { runCatching { PackId(it) }.getOrNull() }
            ?: return@coroutineScope Result.failure(failureData("invalid_pack_id"))
        val action = inputData.getString(PackWorkScheduler.KEY_ACTION)
            ?.let { text -> PackWorkAction.entries.firstOrNull { it.name == text } }
            ?: return@coroutineScope Result.failure(failureData("invalid_action"))

        // Application.onCreate must bind the runtime, including during a WorkManager cold start.
        val runtime = try {
            PackWorkerRuntimeProvider.resolve(applicationContext)
        } catch (exception: Exception) {
            Log.e(TAG, "Cannot create pack worker runtime", exception)
            null
        } ?: return@coroutineScope Result.failure(failureData("runtime_unavailable"))

        val manager = runtime.packManager
        val notification = PackWorkNotification(applicationContext)
        fun packName(): String = manager.states.value[packId]?.manifest?.displayName ?: packId.value.toString()
        var progressJob: Job? = null
        try {
            // Android may start a dependent WorkRequest after the app has moved to the
            // background, where starting WorkManager's foreground service is forbidden.
            // The JobScheduler work itself remains valid and can continue without it.
            var foregroundAvailable = try {
                setForeground(notification.create(packId, action, packName()))
                true
            } catch (denied: IllegalStateException) {
                Log.w(TAG, "pack=${packId.value} action=$action foreground unavailable; continuing work", denied)
                false
            }
            progressJob = launch {
                manager.installProgress
                    .map { it[packId] }
                    .distinctUntilChangedBy { progress ->
                        Triple(progress?.phase,
                            ((progress?.downloadFraction ?: 0f) * 100f).toInt(),
                            progress?.coverDownloaded)
                    }
                    .collect { progress ->
                        if (progress == null) return@collect
                        setProgress(Data.Builder()
                            .putString("phase", progress.phase.name)
                            .putLong("completed", progress.downloadedBytes)
                            .putLong("total", progress.totalDownloadBytes)
                            .putInt("cover_downloaded", progress.coverDownloaded)
                            .putInt("cover_target", progress.coverTarget)
                            .build())
                        if (foregroundAvailable) {
                            try {
                                setForeground(notification.create(packId, action, packName(), progress))
                            } catch (denied: IllegalStateException) {
                                foregroundAvailable = false
                                Log.w(TAG, "pack=${packId.value} action=$action foreground update unavailable", denied)
                            }
                        }
                    }
            }

            runtime.prepare(packId, action)
            val disposition = when (action) {
                PackWorkAction.INSTALL -> {
                    val sequence = inputData.getLong(PackWorkScheduler.KEY_QUEUE_SEQUENCE, 0L)
                    if (sequence > 0L) {
                        setProgress(Data.Builder().putString("phase", PackInstallPhase.QUEUED.name).build())
                        awaitPersistentPackDownloadTurn(applicationContext, sequence)
                    }
                    manager.downloadAndInstall(packId).also { result ->
                        if (result != PackOperationResult.INSTALLED) {
                            Log.w(TAG, "pack=${packId.value} install result=$result attempt=$runAttemptCount")
                        }
                    }.toWorkDisposition()
                }
                PackWorkAction.RECOVER -> if (manager.recover(packId)) {
                    PackWorkDisposition.SUCCESS
                } else PackWorkDisposition.RETRYABLE
                PackWorkAction.INITIAL_COVERS -> if (runtime.resumeInitialCoversIfNeeded(packId)) {
                    PackWorkDisposition.SUCCESS
                } else PackWorkDisposition.RETRYABLE
            }

            // The INSTALL -> INITIAL_COVERS dependency is enqueued atomically by WorkManager.
            // No second status file, no manual rescheduling, no remembered cancellation.
            disposition.toResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Log.e(TAG, "pack=${packId.value} action=$action attempt=$runAttemptCount", exception)
            retryOrFail("exception")
        } finally {
            withContext(NonCancellable) {
                progressJob?.cancelAndJoin()
                // Cheap state discovery only. Never scan storage in a Worker finally block.
                runCatching { manager.refresh() }
                    .onFailure { Log.w(TAG, "pack=${packId.value} refresh failed", it) }
            }
        }
    }

    private fun PackWorkDisposition.toResult(): Result = when (this) {
        PackWorkDisposition.SUCCESS -> Result.success()
        PackWorkDisposition.PERMANENT_FAILURE -> Result.failure(failureData("permanent"))
        PackWorkDisposition.RETRYABLE -> retryOrFail("retry_exhausted")
    }

    private fun retryOrFail(reason: String): Result =
        if (runAttemptCount + 1 < PackWorkScheduler.MAX_ATTEMPTS) Result.retry()
        else Result.failure(failureData(reason))

    private fun failureData(reason: String): Data = Data.Builder()
        .putString(PackWorkScheduler.KEY_FAILURE_REASON, reason)
        .putInt(PackWorkScheduler.KEY_ATTEMPTS, runAttemptCount + 1)
        .build()

    private companion object { const val TAG = "RankOfflinePackWork" }
}
