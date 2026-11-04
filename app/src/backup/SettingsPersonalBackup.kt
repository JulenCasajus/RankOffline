package org.rankoffline.app

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Personal-only JSON (schema 2): never includes .pack binaries, catalogs or covers. */
@Composable
internal fun SettingsPersonalBackupScreen(
    manifests: List<PackManifest>,
    scope: ViewingScope,
    busy: Boolean,
    onScope: (ViewingScope) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.settings_data_title), onBack) {
        val bottomClearance = LocalFloatingNavigationClearance.current
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + bottomClearance),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Choose the personal data to transfer", style = MaterialTheme.typography.titleMedium)
            Text("Backups contain ratings, notes, rating profiles and personal preferences. " +
                "They do not contain installed packs, catalogs or image files.",
                style = MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = scope == ViewingScope.All,
                        onClick = { onScope(ViewingScope.All) }, label = { Text("Entire app") })
                }
                items(manifests, key = { it.packId.value }) { manifest ->
                    FilterChip(selected = scope == ViewingScope.Pack(manifest.packId),
                        onClick = { onScope(ViewingScope.Pack(manifest.packId)) },
                        label = { Text(manifest.displayName) })
                }
            }
            Button(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Export JSON backup")
            }
            OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Import JSON backup")
            }
            TextButton(onClick = onShare, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Share backup JSON")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Replace affects only the selected scope. Merge keeps unrelated packs untouched. " +
                "A pack-only file cannot be imported as an entire-app backup.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
