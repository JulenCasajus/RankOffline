package org.rankoffline.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

private data class PodiumArtwork(val drawable: Int, val sourceWidth: Float, val sourceHeight: Float)
private fun podiumArtwork(position: Int): PodiumArtwork = when (position) {
    1 -> PodiumArtwork(R.drawable.rankoffline_podium_1, 525f, 400f)
    2 -> PodiumArtwork(R.drawable.rankoffline_podium_2, 487f, 341f)
    else -> PodiumArtwork(R.drawable.rankoffline_podium_3, 483f, 310f)
}

/** Single scale for all 3 artworks, preserving the original podium's visual proportions. */
@Composable
internal fun RankingPodium(
    entries: List<MediaListEntry>, sortOrder: RankingSortOrder,
    settings: AppSettingsState, covers: CoverRepository, cache: CoverBitmapCache,
    onDownload: (CatalogEntryKey) -> Unit, onOpenCover: (MediaListEntry) -> Unit,
    onOpenRating: (MediaListEntry) -> Unit
) {
    val positions = when (entries.size) {
        0 -> emptyList()
        1 -> listOf(0 to 1)
        2 -> listOf(1 to 2, 0 to 1)
        else -> listOf(1 to 2, 0 to 1, 2 to 3)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 6.dp
        val scale = ((maxWidth - gap * 2).coerceAtLeast(0.dp).value / 1495f).coerceAtLeast(0f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom) {
            positions.forEach { (index, place) ->
                val entry = entries[index]
                val art = podiumArtwork(place)
                val width = (art.sourceWidth * scale).dp
                val height = (art.sourceHeight * scale).dp
                val coverSize = minOf(if (place == 1) 120.dp else 96.dp, width)
                val shown = entry.scoreForList(sortOrder)
                Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
                    ScoreValueBadge(shown, onClick = { onOpenRating(entry) })
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(width).height(coverSize + height - 8.dp)) {
                        MediaCover(entry, settings, covers, cache,
                            onDownloadRequested = onDownload,
                            onCoverClick = { onOpenCover(entry) },
                            showImages = true,
                            modifier = Modifier.size(coverSize).align(Alignment.TopCenter)
                                .clip(RoundedCornerShape(12.dp)).zIndex(1f))
                        Image(painterResource(art.drawable), null,
                            modifier = Modifier.size(width, height).align(Alignment.BottomCenter),
                            contentScale = ContentScale.Fit)
                    }
                }
            }
        }
    }
}
