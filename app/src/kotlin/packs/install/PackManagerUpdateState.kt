package org.rankoffline.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal fun PackManager.markUpdateFailed(id: PackId) {
    updateFailures.add(id)
    synchronized(stateLock) {
        _states.value[id]?.let { current ->
            _states.value = _states.value + (id to current.copy(updateFailed = true))
        }
    }
}

/** Reconcile files and Room even if cancellation lands after the Room transaction. */
internal suspend fun PackManager.reconcileInterruptedUpdate(id: PackId): Boolean =
    withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            val phase = installer.recover(id)
            val directory = requireNotNull(installer.installedDirectory(id))
            val manifest = requireNotNull(readInstalledManifest(directory))
            withImportTurn { synchronizer.sync(manifest, directory) }
            if (phase == PackRecoveryState.AWAITING_UPDATE_COMMIT) installer.commitUpdate(id)
        }.isSuccess
    }
