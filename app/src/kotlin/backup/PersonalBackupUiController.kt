package org.rankoffline.app

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Coordinates user-initiated backups without loading the backup service at startup. */
internal class PersonalBackupUiController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val backup: () -> PersonalDataBackupManager,
    private val onRestored: () -> Unit,
    private val onError: suspend (Throwable) -> Unit
) {
    private val coordinator = PersonalDataTransferCoordinator()
    private val previewState = MutableStateFlow<PersonalBackupPreview?>(null)
    val preview: StateFlow<PersonalBackupPreview?> = previewState.asStateFlow()
    val transfer: StateFlow<PersonalDataTransferState> = coordinator.state
    private val pendingShareState = MutableStateFlow<Uri?>(null)
    val pendingShare: StateFlow<Uri?> = pendingShareState.asStateFlow()

    private fun run(operation: PersonalDataTransferOperation, block: suspend () -> Unit) = scope.launch {
        try {
            if (!coordinator.run(operation, block)) {
                onError(IllegalStateException("Personal data transfer already active"))
            }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Log.e("RankOfflineBackup", "Backup operation failed", failure); onError(failure) }
    }
    fun export(uri: Uri, scope: ViewingScope) = run(PersonalDataTransferOperation.EXPORTING) {
        backup().export(uri, scope)
    }
    fun importPreview(uri: Uri) = run(PersonalDataTransferOperation.VALIDATING_IMPORT) {
        previewState.value = backup().preview(uri)
    }
    fun dismissPreview() { if (!transfer.value.active) previewState.value = null }
    fun import(mode: PersonalDataImportMode, scope: ViewingScope) {
        val candidate = previewState.value ?: return
        run(PersonalDataTransferOperation.IMPORTING) {
            backup().import(candidate, mode, scope)
            previewState.value = null
            onRestored()
        }
    }
    fun share(scope: ViewingScope) = run(PersonalDataTransferOperation.SHARING) {
        val file = backup().exportForSharing(scope)
        pendingShareState.value = FileProvider.getUriForFile(context,
            "${context.packageName}.personal-backups", file)
    }
    fun shareHandled() { pendingShareState.value = null }
}
