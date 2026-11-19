package org.rankoffline.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Presents only proven newer revisions; the existing WorkManager path performs each update. */
@Composable
internal fun PackUpdateDialog(
    targets: List<Pair<PackId, String>>,
    states: Map<PackId, PackState>,
    onUpdate: (PackId) -> Unit,
    onLater: () -> Unit
) {
    val languageTag = LocalConfiguration.current.locales[0]?.toLanguageTag() ?: "en"
    val entries = remember(states, languageTag) {
        packManagementEntries(states, languageTag).associateBy { it.packId }
    }
    val allDone = targets.all { (id, version) ->
        states[id]?.manifest?.version == version && states[id]?.status == PackRuntimeStatus.READY
    }
    WelcomeDialogBackground { availableHeight ->
        Column(Modifier.heightIn(max = availableHeight).testTag("pack_update_dialog")
            .padding(20.dp)) {
            Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pack_updates_title),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.pack_updates_description))
                Text(stringResource(R.string.pack_updates_personal_data),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 4.dp))
                targets.forEach { (id, version) ->
                    val state = states[id]
                    val entry = entries[id]
                    val accent = entry?.accentArgb?.let(::Color) ?: MaterialTheme.colorScheme.primary
                    val completed = state?.manifest?.version == version &&
                        state.status == PackRuntimeStatus.READY && state.progress == null
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(15.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(mediaScreenTitle("RankOffline", entry?.name ?: id.directoryName(), accent),
                                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.pack_updates_versions,
                                        state?.manifest?.version ?: "—", version),
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(if (completed) stringResource(R.string.pack_updates_done)
                                        else if (state?.updateFailed == true)
                                            stringResource(R.string.pack_updates_error)
                                        else state?.let { packStatusText(it) }
                                            ?: stringResource(R.string.category_not_installed),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if (state?.canUpdate == true) PackCloudDownloadButton(
                                    onClick = { onUpdate(id) }, tint = accent,
                                    contentDescription = stringResource(R.string.pack_updates_action))
                            }
                            state?.progress?.let { progress ->
                                if (progress.phase == PackInstallPhase.DOWNLOADING)
                                    LinearProgressIndicator(progress = { progress.downloadFraction },
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = accent)
                                else LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = accent)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
