package org.rankoffline.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

/** Group identity and member identity remain distinct, including their ratings. */
@Composable
internal fun GroupDetailScreen(
    detail: MediaGroupDetail, settings: AppSettingsState,
    covers: CoverRepository, cache: CoverBitmapCache,
    onBack: () -> Unit, onRate: (fromMembers: Boolean) -> Unit,
    onMember: (MediaItem) -> Unit, onCoverDownload: (CatalogEntryKey) -> Unit,
    onCoverOpen: (MediaListEntry) -> Unit
) {
    BackHandler(onBack = onBack)
    var askUseMembers by remember(detail.group.key) { mutableStateOf(false) }
    val explicit = detail.explicitRating?.finalScore
    val derived = detail.derivedRating.finalScore
    val shown = explicit ?: derived
    val request = {
        if (explicit == null && detail.derivedRating.ratedCount > 0) askUseMembers = true
        else onRate(false)
    }
    Scaffold(topBar = { RankOfflineTopAppBar(detail.group.displayTitle, onBack) }, containerColor = Bg) { inset ->
        Column(Modifier.fillMaxSize().padding(inset).padding(horizontal = 16.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(detail.group.displayTitle, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.group_entries_count, detail.members.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.group_rated_count,
                        detail.derivedRating.ratedCount, detail.members.size))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        ScoreValueBadge(shown, onClick = request)
                        OutlinedButton(onClick = request) {
                            Text(stringResource(R.string.group_rate_together))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.group_members), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                items(detail.members, key = { "${it.packId.value}:${it.id}" }) { item ->
                    val entry = MediaListEntry.Item(item)
                    MediaEntryRow(entry, settings, covers, cache,
                        showImages = settings.mediaSettings[item.packId]?.showImages == true,
                        onClick = { onMember(item) }, onGroupDetails = {},
                        onCoverDownload = onCoverDownload, onCoverOpen = { onCoverOpen(entry) })
                }
            }
        }
    }
    if (askUseMembers) AlertDialog(
        onDismissRequest = { askUseMembers = false },
        title = { Text(stringResource(R.string.group_rate_together)) },
        text = { Text(stringResource(R.string.group_draft_choice)) },
        confirmButton = { TextButton(onClick = { askUseMembers = false; onRate(true) }) {
            Text(stringResource(R.string.group_use_member_ratings))
        } },
        dismissButton = { TextButton(onClick = { askUseMembers = false; onRate(false) }) {
            Text(stringResource(R.string.group_start_fresh))
        } }
    )
}
