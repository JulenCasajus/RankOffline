package org.rankoffline.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** Only schedules durable operations; never downloads or imports any pack content. */
internal object PackWorkScheduler {
    const val KEY_PACK_ID = "pack_id"
    const val KEY_ACTION = "action"
    const val KEY_FAILURE_REASON = "failure_reason"
    const val KEY_ATTEMPTS = "attempts"
    const val MAX_ATTEMPTS = 3

    private const val ALL_TAG = "rankoffline-pack-work"
    private const val PACK_TAG_PREFIX = "rankoffline-pack-id:"
    private const val ACTION_TAG_PREFIX = "rankoffline-pack-action:"

    fun enqueue(context: Context, packId: PackId, action: PackWorkAction) {
        val work = WorkManager.getInstance(context)
        if (action == PackWorkAction.INSTALL) {
            // Durable chain: initial covers cannot be lost if the process dies immediately
            // after committing Room but before scheduling a second WorkRequest.
            work.beginUniqueWork(uniqueName(packId, action), ExistingWorkPolicy.KEEP,
                request(packId, PackWorkAction.INSTALL))
                .then(request(packId, PackWorkAction.INITIAL_COVERS))
                .enqueue()
        } else {
            work.enqueueUniqueWork(uniqueName(packId, action), ExistingWorkPolicy.KEEP,
                request(packId, action))
        }
    }

    fun cancel(context: Context, packId: PackId) {
        val manager = WorkManager.getInstance(context)
        PackWorkAction.entries.forEach { manager.cancelUniqueWork(uniqueName(packId, it)) }
    }

    /** Cancels ONLY the optional stage; doesn't cancel the already-running install. */
    suspend fun cancelInitialCovers(context: Context, packId: PackId) = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context)
        val installChain = manager.getWorkInfosForUniqueWork(uniqueName(packId, PackWorkAction.INSTALL)).get()
        val separate = manager.getWorkInfosForUniqueWork(uniqueName(packId, PackWorkAction.INITIAL_COVERS)).get()
        (installChain + separate)
            .filter { it.tags.contains(actionTag(PackWorkAction.INITIAL_COVERS)) && !it.state.isFinished }
            .forEach { manager.cancelWorkById(it.id) }
    }

    fun request(packId: PackId, action: PackWorkAction): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<PackBackgroundWorker>()
            .setInputData(Data.Builder()
                .putLong(KEY_PACK_ID, packId.value)
                .putString(KEY_ACTION, action.name)
                .build())
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(if (action == PackWorkAction.RECOVER) {
                    NetworkType.NOT_REQUIRED
                } else {
                    NetworkType.CONNECTED
                }).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(ALL_TAG)
            .addTag(packTag(packId))
            .addTag(actionTag(action))
            .build()

    internal fun uniqueName(packId: PackId, action: PackWorkAction): String =
        "rankoffline-pack-${packId.value}-${action.name.lowercase(java.util.Locale.ROOT)}"

    /** Restore work state after process recreation and after remote descriptors are loaded. */
    suspend fun reconcilePersistentWork(context: Context, manager: PackManager) =
        withContext(Dispatchers.IO) {
            reconcile(manager, WorkManager.getInstance(context).getWorkInfosByTag(ALL_TAG).get())
        }

    /** Observe every terminal transition, including failures before downloadAndInstall starts. */
    suspend fun observePersistentWork(context: Context, manager: PackManager) {
        WorkManager.getInstance(context).getWorkInfosByTagFlow(ALL_TAG).collect { infos ->
            reconcile(manager, infos)
        }
    }

    private suspend fun reconcile(manager: PackManager, infos: List<WorkInfo>) {
        val installs = infos.filter { it.tags.contains(actionTag(PackWorkAction.INSTALL)) }
        val active = installs.filterNot { it.state.isFinished }.mapNotNull(::packIdFromTags).toSet()
        val failed = installs.filter {
            it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED
        }.mapNotNull(::packIdFromTags).toSet()
        manager.reconcileQueuedInstallStates(active, failed)
    }

    private fun packIdFromTags(info: WorkInfo): PackId? = info.tags
        .firstOrNull { it.startsWith(PACK_TAG_PREFIX) }
        ?.removePrefix(PACK_TAG_PREFIX)
        ?.let { runCatching { PackId.parse(it) }.getOrNull() }

    private fun packTag(packId: PackId) = PACK_TAG_PREFIX + packId.value.toString()
    private fun actionTag(action: PackWorkAction) = ACTION_TAG_PREFIX + action.name

}
