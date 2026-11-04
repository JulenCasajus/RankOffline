package org.rankoffline.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Compact controls recovered from v0.2.1; all labels and choices remain manifest-driven. */
@Composable
internal fun CatalogActions(
    canMerge: Boolean,
    merge: Boolean,
    onMerge: (Boolean) -> Unit,
    mergeLabel: String,
    activeFilters: Int,
    onFilters: () -> Unit,
    sortLabel: String,
    sortReversed: Boolean,
    onSort: () -> Unit,
    onDirection: () -> Unit,
    sortMenu: @Composable () -> Unit
) {
    val filterLabel = if (activeFilters > 0) {
        stringResource(R.string.filters_active, activeFilters)
    } else stringResource(R.string.filters_title)
    val currentDirection = stringResource(if (sortReversed) {
        R.string.sort_direction_ascending
    } else R.string.sort_direction_descending)
    val nextDirection = stringResource(if (sortReversed) {
        R.string.sort_direction_descending
    } else R.string.sort_direction_ascending)
    val directionDescription = stringResource(
        R.string.sort_direction_accessibility, currentDirection, nextDirection
    )
    val labels = listOfNotNull(mergeLabel.takeIf { canMerge }, filterLabel, sortLabel)
    val style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val weights = remember(labels, style, density) {
        labels.mapIndexed { index, label ->
            val text = measurer.measure(label, style = style, maxLines = 1).size.width
            maxOf(text + with(density) { 8.dp.toPx() }, with(density) { 40.dp.toPx() }) +
                if (index == labels.lastIndex) with(density) { 49.dp.toPx() } else 0f
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        var index = 0
        if (canMerge) CompactCatalogAction(mergeLabel, merge, { onMerge(!merge) },
            Modifier.weight(weights[index++]))
        CompactCatalogAction(filterLabel, activeFilters > 0, onFilters,
            Modifier.weight(weights[index++]))
        Surface(Modifier.weight(weights[index]).height(48.dp), RoundedCornerShape(12.dp),
            color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onSort),
                    contentAlignment = Alignment.Center) {
                    Text(sortLabel, maxLines = 1, softWrap = false,
                        overflow = TextOverflow.Ellipsis, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 4.dp))
                    sortMenu()
                }
                VerticalDivider(Modifier.height(28.dp), color = MaterialTheme.colorScheme.outline)
                Box(Modifier.width(48.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    .clickable(onClick = onDirection)
                    .semantics { contentDescription = directionDescription },
                    contentAlignment = Alignment.Center) {
                    Text(if (sortReversed) "↑" else "↓",
                        color = MaterialTheme.colorScheme.primary, fontSize = 19.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CompactCatalogAction(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
        Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            fontSize = 11.sp)
    }
}
