package org.rankoffline.app

import android.content.Context

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
