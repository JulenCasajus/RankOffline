package org.rankoffline.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Presents only proven newer revisions; the existing WorkManager path performs each update. */
@Composable
internal fun PackUpdateDialog(
    targets: List<Pair<PackId, String>>,
    states: Map<PackId, PackState>,
    onUpdate: (PackId) -> Unit,
    onLater: () -> Unit
) {
    val languageTag = LocalConfiguration.current.locales[0]?.toLanguageTag() ?: "en"
    val allDone = targets.all { (id, version) ->
        states[id]?.manifest?.version == version && states[id]?.status == PackRuntimeStatus.READY
    }
    Dialog(onDismissRequest = {}, properties = DialogProperties(
        dismissOnBackPress = false, dismissOnClickOutside = false,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false
    )) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)) {
                Text(stringResource(R.string.pack_updates_title),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.pack_updates_description),
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                Text(stringResource(R.string.pack_updates_personal_data),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(targets, key = { it.first.value }) { (id, version) ->
                        val state = states[id]
                        val completed = state?.manifest?.version == version &&
                            state.status == PackRuntimeStatus.READY && state.progress == null
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(15.dp)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text("${state?.manifest?.localizedName(languageTag)
                                    ?: state?.manifest?.displayName ?: id.directoryName()} · ${id.directoryName()}",
                                    fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.pack_updates_versions,
                                    state?.manifest?.version ?: "—", version),
                                    style = MaterialTheme.typography.bodySmall)
                                Text(if (completed) stringResource(R.string.pack_updates_done)
                                    else if (state?.updateFailed == true)
                                        stringResource(R.string.pack_updates_error)
                                    else state?.let { packStatusText(it) }
                                        ?: stringResource(R.string.category_not_installed),
                                    style = MaterialTheme.typography.bodySmall)
                                state?.progress?.let { progress ->
                                    if (progress.phase == PackInstallPhase.DOWNLOADING)
                                        LinearProgressIndicator(progress = { progress.downloadFraction },
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                                    else LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                                }
                                if (state?.canUpdate == true) TextButton(onClick = { onUpdate(id) }) {
                                    Text(stringResource(R.string.pack_updates_action))
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onLater, modifier = Modifier.weight(1f)) {
                        Text(stringResource(if (allDone) R.string.action_continue else R.string.pack_updates_later))
                    }
                    Button(onClick = { targets.forEach { (id, _) ->
                        if (states[id]?.canUpdate == true) onUpdate(id)
                    } }, enabled = targets.any { (id, _) -> states[id]?.canUpdate == true },
                        modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.pack_updates_all))
                    }
                }
            }
        }
    }
}
