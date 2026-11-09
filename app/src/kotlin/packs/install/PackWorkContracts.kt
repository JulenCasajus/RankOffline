package org.rankoffline.app

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first

private const val SEQUENCE_TAG_PREFIX = "rankoffline-pack-sequence:"
private const val QUEUE_PREFERENCES = "rankoffline_pack_work_queue"
private const val NEXT_SEQUENCE = "next_install_sequence"
private val sequenceLock = Any()

/** WorkManager only coordinates persistent operations; PackManager owns their implementation. */
internal enum class PackWorkAction { INSTALL, RECOVER, INITIAL_COVERS }
internal enum class PackWorkDisposition { SUCCESS, RETRYABLE, PERMANENT_FAILURE }

internal fun PackOperationResult.toWorkDisposition(): PackWorkDisposition = when (this) {
    PackOperationResult.INSTALLED -> PackWorkDisposition.SUCCESS
    PackOperationResult.NETWORK_ERROR,
    PackOperationResult.IMPORT_ERROR,
    PackOperationResult.STORAGE_ERROR -> PackWorkDisposition.RETRYABLE
    PackOperationResult.NOT_FOUND,
    PackOperationResult.INVALID_PACK -> PackWorkDisposition.PERMANENT_FAILURE
}

/** Persistent FIFO identity stored in WorkManager input/tags; no second operation queue. */
internal fun nextPackInstallSequence(context: Context): Long = synchronized(sequenceLock) {
    val preferences = context.getSharedPreferences(QUEUE_PREFERENCES, Context.MODE_PRIVATE)
    val previous = preferences.getLong(NEXT_SEQUENCE, 0L)
    check(previous < Long.MAX_VALUE) { "Pack install sequence overflow" }
    val next = previous + 1L
    check(preferences.edit().putLong(NEXT_SEQUENCE, next).commit()) {
        "Unable to persist pack install order"
    }
    next
}

internal fun packInstallSequenceTag(sequence: Long): String = SEQUENCE_TAG_PREFIX + sequence

/** Wait only for earlier archive transfers; their later local import may continue in parallel. */
internal suspend fun awaitPersistentPackDownloadTurn(context: Context, sequence: Long) {
    require(sequence > 0L)
    WorkManager.getInstance(context).getWorkInfosByTagFlow(PackWorkScheduler.ALL_TAG).first { infos ->
        infos.none { info -> info.blocksArchiveSequence(sequence) }
    }
}

internal fun WorkInfo.blocksArchiveSequence(sequence: Long): Boolean {
    if (state.isFinished || tags.none { it.endsWith(":INSTALL") }) return false
    val phase = progress.getString("phase")
    if (phase != null && phase != PackInstallPhase.QUEUED.name &&
        phase != PackInstallPhase.DOWNLOADING.name) return false
    val other = tags.firstNotNullOfOrNull { tag ->
        tag.removePrefix(SEQUENCE_TAG_PREFIX).takeIf { tag.startsWith(SEQUENCE_TAG_PREFIX) }?.toLongOrNull()
    } ?: return true
    return other < sequence
}

internal interface PackWorkerRuntime {
    val packManager: PackManager

    /** Rehydrates trusted source descriptors after process death for INSTALL. */
    suspend fun prepare(packId: PackId, action: PackWorkAction)

    /** Initial cover batch is optional; cancellation is owned by WorkManager, never persisted. */
    suspend fun resumeInitialCoversIfNeeded(packId: PackId): Boolean
}

internal object PackWorkerRuntimeProvider {
    @Volatile private var factory: ((Context) -> PackWorkerRuntime)? = null

    fun configure(factory: (Context) -> PackWorkerRuntime) {
        this.factory = factory
    }

    fun resolve(context: Context): PackWorkerRuntime? = factory?.invoke(context.applicationContext)
}
