package org.rankoffline.app

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** UI-only phase mapping; no status persistence or duplicate job state. */
@Composable
private fun packStatusText(state: PackState): String {
    val progress = state.progress
    return when (progress?.phase) {
        PackInstallPhase.QUEUED -> stringResource(R.string.category_phase_waiting_download)
        PackInstallPhase.DOWNLOADING -> stringResource(
            R.string.category_phase_downloading_progress,
            progress.downloadedBytes / 1_000_000.0,
            progress.totalDownloadBytes / 1_000_000.0
        )
        PackInstallPhase.VERIFYING -> stringResource(R.string.category_phase_verifying)
        PackInstallPhase.INSTALLING -> stringResource(R.string.category_phase_installing)
        PackInstallPhase.WAITING_IMPORT -> stringResource(R.string.category_phase_waiting_import)
        PackInstallPhase.IMPORTING -> stringResource(R.string.category_phase_importing_catalog)
        PackInstallPhase.FINALIZING -> stringResource(R.string.category_phase_finalizing)
        PackInstallPhase.WAITING_COVERS -> stringResource(R.string.category_phase_waiting_covers)
        PackInstallPhase.DOWNLOADING_COVERS -> stringResource(
            R.string.category_phase_downloading_covers,
            progress.coverProcessed,
            progress.coverTarget
        )
        null -> when (state.status) {
            PackRuntimeStatus.READY -> stringResource(R.string.category_downloaded) +
                if (state.storage.current) {
                    " · " + Formatter.formatFileSize(LocalContext.current, state.storage.installedBytes)
                } else ""
            PackRuntimeStatus.FILES_INSTALLED,
            PackRuntimeStatus.INITIALIZING -> stringResource(R.string.category_phase_waiting_import)
            PackRuntimeStatus.DELETING -> stringResource(R.string.category_deleting)
            PackRuntimeStatus.ERROR -> stringResource(R.string.category_pack_runtime_error)
            PackRuntimeStatus.NOT_INSTALLED -> stringResource(R.string.category_not_installed)
        }
    }
}

@Composable
internal fun PackManagementRow(
    entry: PackManagementEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onInstall: () -> Unit,
    onCancelInitialCovers: () -> Unit,
    onRemove: () -> Unit,
    onRequestStorageDetails: () -> Unit
) {
    val state = entry.state
    val accent = entry.accentArgb?.let(::Color) ?: MaterialTheme.colorScheme.primary
    var expanded by rememberSaveable(entry.packId.value) { mutableStateOf(false) }
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = if (selected) BorderStroke(2.dp, accent) else null,
        shape = RoundedCornerShape(15.dp),
        modifier = Modifier.fillMaxWidth().testTag("pack_row_${entry.packId.value}")
            .clickable(enabled = state.available, role = Role.RadioButton, onClick = onSelect)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = mediaScreenTitle("RankOffline", entry.name, accent),
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = packStatusText(state), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.progress?.takeIf { it.phase == PackInstallPhase.DOWNLOADING_COVERS }
                        ?.let { progress ->
                            Text(
                                text = stringResource(R.string.covers_batch_outcomes,
                                    progress.coverDownloaded, progress.coverNotFound, progress.coverErrors),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    if (state.archiveDownloadBytes != null && !state.installed && state.progress == null) {
                        Text(
                            text = stringResource(
                                R.string.pack_storage_transfer,
                                Formatter.formatFileSize(context, state.archiveDownloadBytes)
                            ),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (selected) {
                    Icon(Icons.Default.CheckCircle, null, tint = accent,
                        modifier = Modifier.size(22.dp).testTag("pack_selected_${entry.packId.value}"))
                }
                if (state.installed && state.progress == null) {
                    IconButton(onClick = {
                        expanded = !expanded
                        if (expanded && !state.storage.current) onRequestStorageDetails()
                    }) {
                        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = stringResource(R.string.pack_storage_details),
                            modifier = Modifier.offset(x = 6.dp))
                    }
                }
                when {
                    state.progress?.phase == PackInstallPhase.WAITING_COVERS ||
                        state.progress?.phase == PackInstallPhase.DOWNLOADING_COVERS ->
                        IconButton(onClick = onCancelInitialCovers) {
                            Icon(Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_cancel), tint = accent)
                        }
                    state.progress != null -> Unit
                    state.canDelete -> IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete,
                            contentDescription = stringResource(R.string.category_remove_catalog), tint = accent)
                    }
                    state.canDownload -> IconButton(onClick = onInstall) {
                        Icon(
                            if (state.status == PackRuntimeStatus.ERROR) Icons.Default.Refresh
                            else Icons.Default.CloudDownload,
                            contentDescription = stringResource(R.string.category_download), tint = accent)
                    }
                    else -> Icon(Icons.Default.Lock,
                        contentDescription = stringResource(R.string.category_download_unavailable),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.progress?.let { progress ->
                when (progress.phase) {
                    PackInstallPhase.DOWNLOADING -> LinearProgressIndicator(
                        progress = { progress.downloadFraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = accent
                    )
                    PackInstallPhase.DOWNLOADING_COVERS -> LinearProgressIndicator(
                        progress = {
                            if (progress.coverTarget <= 0) 0f
                            else (progress.coverProcessed.toFloat() / progress.coverTarget).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = accent
                    )
                    else -> LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = accent)
                }
            }
            if (state.status == PackRuntimeStatus.DELETING && state.progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = accent)
            }
            if (expanded && state.installed && state.progress == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.pack_storage_physical_total), fontSize = 12.sp)
                    Text(
                        if (state.storage.current) Formatter.formatFileSize(context, state.storage.installedBytes)
                        else "—",
                        fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.pack_storage_covers), fontSize = 12.sp)
                    Text(
                        if (state.storage.current) Formatter.formatFileSize(context, state.storage.coverBytes)
                        else "—", fontSize = 12.sp
                    )
                }
            }
        }
    }
}
