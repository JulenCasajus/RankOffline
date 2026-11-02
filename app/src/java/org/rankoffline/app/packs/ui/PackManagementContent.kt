package org.rankoffline.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.Collator
import java.util.Locale

/** Display-only identity; never infer the pack ID from its translated name. */
internal data class PackManagementEntry(val state: PackState, val name: String, val accentArgb: Int?) {
    val packId: PackId get() = state.packId
}

/** Include remote, installed and broken installations; deterministic locale-aware ordering. */
internal fun packManagementEntries(
    packs: Map<PackId, PackState>,
    languageTag: String
): List<PackManagementEntry> {
    val locale = Locale.forLanguageTag(languageTag)
    val collator = Collator.getInstance(locale)
    return packs.values.map { state ->
        val manifest = state.manifest
        PackManagementEntry(
            state = state,
            name = manifest?.localizedName(locale.toLanguageTag())?.trim()
                ?.ifEmpty { null } ?: manifest?.displayName?.takeIf { it.isNotBlank() }
                ?: state.packId.value.toString(),
            accentArgb = manifest?.presentation?.accentColor?.let(::packAccentArgbOrNull)
        )
    }.sortedWith { left, right ->
        val compared = collator.compare(left.name, right.name)
        if (compared != 0) compared else left.packId.value.compareTo(right.packId.value)
    }
}

/** Keep a remembered pack selected only once its catalog is actually ready. */
@Composable
internal fun PackOnboardingDialog(
    packStates: Map<PackId, PackState>,
    languageTag: String,
    activePackId: PackId?,
    onDiscoverPacks: () -> Unit,
    onInstallPack: (PackId) -> Unit,
    onCancelInitialCovers: (PackId) -> Unit,
    onUninstallPack: (PackId) -> Unit,
    onPurgePack: (PackId) -> Unit,
    onPackSelected: (PackId) -> Unit,
    onRequestStorageDetails: (PackId) -> Unit,
    onComplete: () -> Unit
) {
    // First-run screen only: resolve trusted remote pack sources on entry, never on every
    // recomposition and never during the normal cold-start path after onboarding.
    LaunchedEffect(Unit) { onDiscoverPacks() }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(26.dp),
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.onboarding_categories_title),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.onboarding_categories_subtitle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
                )
                PackManagementContent(
                    packStates = packStates,
                    languageTag = languageTag,
                    selectedPackId = activePackId,
                    onDiscoverPacks = onDiscoverPacks,
                    onInstallPack = onInstallPack,
                    onCancelInitialCovers = onCancelInitialCovers,
                    onUninstallPack = onUninstallPack,
                    onPurgePack = onPurgePack,
                    onPackSelected = onPackSelected,
                    onRequestStorageDetails = onRequestStorageDetails,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
                        .testTag("onboarding_done")
                ) {
                    Text(stringResource(R.string.onboarding_done), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Reusable management list for onboarding and the future Settings page. The caller observes
 * PackManager.states and passes the latest map. This function performs NO IO.
 *
 * In particular, storage details are requested ONLY when explicitly expanded.
 */
@Composable
fun PackManagementContent(
    packStates: Map<PackId, PackState>,
    onDiscoverPacks: () -> Unit,
    onInstallPack: (PackId) -> Unit,
    onCancelInitialCovers: (PackId) -> Unit,
    onUninstallPack: (PackId) -> Unit,
    onPurgePack: (PackId) -> Unit,
    onPackSelected: (PackId) -> Unit,
    onRequestStorageDetails: (PackId) -> Unit,
    modifier: Modifier = Modifier,
    selectedPackId: PackId? = null,
    languageTag: String? = null,
    footer: (@Composable () -> Unit)? = null
) {
    val currentLanguageTag = LocalConfiguration.current.locales[0]?.toLanguageTag()
        ?: Locale.getDefault().toLanguageTag()
    val effectiveLanguage = languageTag ?: currentLanguageTag
    val entries = remember(packStates, effectiveLanguage) {
        packManagementEntries(packStates, effectiveLanguage)
    }
    val installedCount = entries.count { it.state.installed }
    var pendingRemovalId by rememberSaveable { mutableStateOf<Long?>(null) }
    val pendingRemoval = entries.firstOrNull { it.packId.value == pendingRemovalId }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = floatingNavigationContentPadding(bottom = 16.dp)
    ) {
        item(key = "summary") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(stringResource(R.string.categories_storage_title), fontWeight = FontWeight.Bold)
                    Text(
                        pluralStringResource(
                            R.plurals.categories_packs_installed, installedCount, installedCount
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item(key = "discover") {
            TextButton(
                onClick = onDiscoverPacks,
                modifier = Modifier.fillMaxWidth().testTag("pack_discover")
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
        items(entries, key = { it.packId.value }) { entry ->
            PackManagementRow(
                entry = entry,
                selected = selectedPackId == entry.packId && entry.state.available,
                onSelect = { onPackSelected(entry.packId) },
                onInstall = { onInstallPack(entry.packId) },
                onCancelInitialCovers = { onCancelInitialCovers(entry.packId) },
                onRemove = { pendingRemovalId = entry.packId.value },
                onRequestStorageDetails = { onRequestStorageDetails(entry.packId) }
            )
        }
        footer?.let { content -> item(key = "footer") { content() } }
    }

    pendingRemoval?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingRemovalId = null },
            title = { Text(stringResource(R.string.category_remove_title, entry.name)) },
            text = { Text(stringResource(R.string.category_remove_catalog)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemovalId = null
                    onPurgePack(entry.packId)
                }) {
                    Text(stringResource(R.string.category_purge_action), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingRemovalId = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(onClick = {
                        pendingRemovalId = null
                        onUninstallPack(entry.packId)
                    }) {
                        Text(stringResource(R.string.category_uninstall_keep_data))
                    }
                }
            }
        )
    }
}
