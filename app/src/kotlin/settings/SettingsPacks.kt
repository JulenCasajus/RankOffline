package org.rankoffline.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*

/** Reuses the onboarding pack-list: one implementation of install/delete/progress dialogs. */
@Composable
internal fun PackSettingsScreen(
    packStates: Map<PackId, PackState>, selectedPackId: PackId?,
    onDiscover: () -> Unit, onInstall: (PackId) -> Unit,
    onCancelInitialCovers: (PackId) -> Unit, onUninstall: (PackId) -> Unit,
    onPurge: (PackId) -> Unit, onSelect: (PackId) -> Unit,
    onStorage: (PackId) -> Unit,
    observePersonalStorage: (PackId) -> kotlinx.coroutines.flow.Flow<PackPersonalStorage>,
    onRankingShare: () -> Unit, onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.categories_title), onBack) {
        PackManagementContent(
            packStates = packStates,
            selectedPackId = selectedPackId,
            onDiscoverPacks = onDiscover,
            onInstallPack = onInstall,
            onCancelInitialCovers = onCancelInitialCovers,
            onUninstallPack = onUninstall,
            onPurgePack = onPurge,
            onPackSelected = onSelect,
            onRequestStorageDetails = onStorage,
            observePersonalStorage = observePersonalStorage,
            modifier = Modifier.fillMaxSize().background(Bg).padding(horizontal = 16.dp),
            footer = {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = onRankingShare, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.ranking_share_title))
                        }
                    }
                }
            }
        )
    }
}

@Composable
internal fun PersonalBackupPreviewDialog(
    preview: PersonalBackupPreview, busy: Boolean,
    onDismiss: () -> Unit, onImport: (PersonalDataImportMode) -> Unit
) {
    var confirmReplace by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.personal_backup_preview_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.personal_backup_preview_body,
                preview.ratingCount, preview.groupRatingCount, preview.packIds.size))
            Text(stringResource(R.string.personal_backup_preview_policy),
                style = MaterialTheme.typography.bodySmall)
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { Row {
            TextButton(onClick = { onImport(PersonalDataImportMode.MERGE_NEWER) }, enabled = !busy) {
                Text(stringResource(R.string.personal_backup_merge))
            }
            TextButton(onClick = { confirmReplace = true }, enabled = !busy) {
                Text(stringResource(R.string.personal_backup_replace), color = MaterialTheme.colorScheme.error)
            }
        } }
    )
    if (confirmReplace) AlertDialog(
        onDismissRequest = { confirmReplace = false },
        title = { Text(stringResource(R.string.personal_backup_replace)) },
        text = { Text(stringResource(R.string.personal_backup_preview_policy)) },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) {
            Text(stringResource(R.string.action_cancel))
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            confirmReplace = false
            onImport(PersonalDataImportMode.REPLACE)
        }) { Text(stringResource(R.string.personal_backup_replace), color = MaterialTheme.colorScheme.error) } }
    )
}
