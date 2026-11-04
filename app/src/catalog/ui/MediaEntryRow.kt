package org.rankoffline.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Entry row is keyed by (pack, kind, ID); artwork can use a different, shared cover key. */
@Composable
internal fun MediaEntryRow(
    entry: MediaListEntry, settings: AppSettingsState,
    coverRepository: CoverRepository, bitmapCache: CoverBitmapCache,
    subtitleFields: List<String> = emptyList(),
    showImages: Boolean, onClick: () -> Unit,
    onGroupDetails: () -> Unit,
    onCoverDownload: (CatalogEntryKey) -> Unit,
    onCoverOpen: () -> Unit,
    rank: Int? = null, rankingSortOrder: RankingSortOrder? = null
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = if (entry is MediaListEntry.Group) onGroupDetails else onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (rank != null) Text("$rank.", modifier = Modifier.width(38.dp), fontWeight = FontWeight.SemiBold)
            if (showImages) {
                MediaCover(entry, settings, coverRepository, bitmapCache,
                    onDownloadRequested = onCoverDownload,
                    onCoverClick = { onCoverOpen() },
                    showImages = true, modifier = Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                val summary = entry.subtitleText(subtitleFields)
                if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (entry) {
                    is MediaListEntry.Group -> Text(
                        stringResource(R.string.group_entries_count, entry.memberCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onGroupDetails))
                    is MediaListEntry.Item -> Unit
                }
            }
            val displayed = when (rankingSortOrder) {
                RankingSortOrder.QUALITY_SCORE -> entry.qualityScore
                RankingSortOrder.PERSONAL_TASTE -> entry.additiveScore
                RankingSortOrder.CATEGORY_SCORE -> entry.categoryScore
                else -> entry.finalScore
            }
            if (displayed != null || rankingSortOrder != null) ScoreValueBadge(displayed)
        }
    }
}

internal fun MediaListEntry.subtitleText(fields: List<String>): String {
    val attributes = when (this) {
        is MediaListEntry.Item -> value.attributes
        is MediaListEntry.Group -> value.attributes
    }
    return fields.asSequence().flatMap { field -> attributes[field].orEmpty().asSequence() }
        .mapNotNull { value -> when (value) {
            is CatalogAttributeValue.Text -> value.value.trim().takeIf(String::isNotEmpty)
            is CatalogAttributeValue.Number -> value.value.toString()
            is CatalogAttributeValue.Flag -> value.value.toString()
        } }.distinct().joinToString(" · ")
}
