package org.rankoffline.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*

/** Batch progress is observed only while this subpage is composed. */
@Composable
internal fun CoversSettingsScreen(
    packId: PackId, packName: String, progress: CoverDownloadProgress,
    downloadedCoverCount: Long?, onDownloadTop: (Int) -> Unit,
    onCancel: () -> Unit, onClear: () -> Unit, onBack: () -> Unit
) {
    var clearConfirmation by remember { mutableStateOf(false) }
    SettingsSubscreen(stringResource(R.string.covers_settings_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = floatingNavigationContentPadding(bottom = 16.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(packName, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.covers_settings_explanation),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        if (downloadedCoverCount != null) {
                            Text(stringResource(R.string.covers_downloaded_count, downloadedCoverCount))
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                        listOf(250,500,1000,5000).forEach { count ->
                            Button(onClick = { onDownloadTop(count) }, enabled = !progress.isActive,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Icon(Icons.Default.Download, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.covers_download_top, count))
                            }
                        }
                        if (progress.target > 0) {
                            Text("${progress.processed}/${progress.target}",
                                modifier = Modifier.padding(top = 8.dp))
                            if (progress.status == CoverDownloadStatus.WAITING_TO_SAVE) {
                                Text(stringResource(R.string.category_phase_covers_waiting_save),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            LinearProgressIndicator(
                                progress = { (progress.processed.toFloat() / progress.target).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth())
                        } else if (progress.isActive) {
                            Text(stringResource(R.string.category_phase_waiting_covers),
                                modifier = Modifier.padding(top = 8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if (progress.target > 0) {
                            Text(stringResource(R.string.covers_batch_outcomes,
                                progress.existing, progress.downloaded,
                                progress.unavailable, progress.errors),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (progress.isActive && progress.status != CoverDownloadStatus.WAITING_TO_SAVE)
                            TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        OutlinedButton(onClick = { clearConfirmation = true },
                            enabled = !progress.isActive, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Delete, null)
                            Text(stringResource(R.string.covers_clear))
                        }
                    }
                }
            }
        }
    }
    if (clearConfirmation) ConfirmationDialog(
        stringResource(R.string.covers_clear_title), stringResource(R.string.covers_clear_explanation),
        onDismiss = { clearConfirmation = false },
        onConfirm = { clearConfirmation = false; onClear() }
    )
}
