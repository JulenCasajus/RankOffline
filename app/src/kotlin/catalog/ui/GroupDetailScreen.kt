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
    subtitleFields: List<PackFieldDefinition> = emptyList(),
    onBack: () -> Unit, onRate: () -> Unit,
    onMember: (MediaItem) -> Unit, onCoverDownload: (CatalogEntryKey) -> Unit,
    onCoverOpen: (MediaListEntry) -> Unit
) {
    BackHandler(onBack = onBack)
    val explicit = detail.explicitRating?.finalScore
    val derived = detail.derivedRating.finalScore
    val shown = explicit ?: derived
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
                        ScoreValueBadge(shown, onClick = onRate)
                        OutlinedButton(onClick = onRate) {
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
                        subtitleFields = subtitleFields,
                        showImages = settings.mediaSettings[item.packId]?.showImages == true,
                        onClick = { onMember(item) }, onGroupDetails = {},
                        onCoverDownload = onCoverDownload, onCoverOpen = { onCoverOpen(entry) })
                }
            }
        }
    }
}
