package org.rankoffline.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Entry row is keyed by (pack, kind, ID); artwork can use a different, shared cover key. */
@Composable
internal fun MediaEntryRow(
    entry: MediaListEntry, settings: AppSettingsState,
    coverRepository: CoverRepository, bitmapCache: CoverBitmapCache,
    subtitleFields: List<PackFieldDefinition> = emptyList(),
    showImages: Boolean, onClick: () -> Unit,
    onGroupDetails: () -> Unit,
    onCoverDownload: (CatalogEntryKey) -> Unit,
    onCoverOpen: () -> Unit,
    rank: Int? = null, rankingSortOrder: RankingSortOrder? = null
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (rank != null) Text("$rank.", modifier = Modifier.width(38.dp), fontWeight = FontWeight.SemiBold)
            if (showImages) {
                MediaCover(entry, settings, coverRepository, bitmapCache,
                    onDownloadRequested = onCoverDownload,
                    onCoverClick = { onCoverOpen() },
                    showImages = true, modifier = Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f).clickable(
                onClick = if (entry is MediaListEntry.Group) onGroupDetails else onClick
            )) {
                Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                CatalogSubtitles(entry, subtitleFields, onGroupDetails)
            }
            val displayed = entry.scoreForList(rankingSortOrder)
            if (displayed != null || rankingSortOrder != null) {
                ScoreValueBadge(displayed, onClick = onClick)
            }
        }
    }
}

/** Ranking scale is presentation only; Room values and SQL ordering stay unchanged. */
internal fun MediaListEntry.scoreForList(sortOrder: RankingSortOrder?): Double? = when (sortOrder) {
    RankingSortOrder.QUALITY_SCORE -> qualityScore
    RankingSortOrder.PERSONAL_TASTE -> additiveScore?.times(10.0)
    RankingSortOrder.CATEGORY_SCORE -> categoryScore
    else -> finalScore
}
