package org.rankoffline.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal const val WHATS_NEW_VERSION = "0.2.2"

internal fun shouldShowWhatsNew(settings: AppSettingsState, versionName: String): Boolean =
    settings.settingsLoaded && settings.hasCompletedOnboarding &&
        versionName.substringBefore('-') == WHATS_NEW_VERSION &&
        settings.lastSeenWhatsNewVersion != WHATS_NEW_VERSION

/** A compact modal that remains readable on small screens and at large font sizes. */
@Composable
internal fun WhatsNewDialog(onContinue: () -> Unit) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(
        dismissOnBackPress = false, dismissOnClickOutside = false,
        usePlatformDefaultWidth = false
    )) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val availableHeight = maxHeight
            Surface(
                modifier = Modifier.fillMaxWidth(0.9f).widthIn(max = 520.dp)
                    .heightIn(max = availableHeight - 48.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(Modifier.padding(20.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(max = (availableHeight - 160.dp).coerceAtLeast(180.dp))
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Text(stringResource(R.string.whats_new_title, WHATS_NEW_VERSION),
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf(
                                R.string.whats_new_visuals,
                                R.string.whats_new_packs,
                                R.string.whats_new_features,
                                R.string.whats_new_bugs
                            ).forEach { message ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text("·", style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(message), style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.whats_new_thanks),
                            style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.whats_new_contact),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = onContinue,
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
                            .testTag("whats_new_continue")) {
                        Text(stringResource(R.string.action_continue), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
