package org.rankoffline.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A themed, generic selector. The caller owns the option list and selected identity.
 * Options may come from a downloaded pack registry, user preferences, or the APK itself.
 * This component neither reads pack manifests nor knows any fixed content categories.
 */
@Composable
fun <T> RankOfflineSelector(
    selectedValue: T,
    values: List<T>,
    title: String,
    icon: ImageVector,
    valueLabel: @Composable (T) -> String,
    onValueSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 56.dp,
    horizontalPadding: Dp = 14.dp,
    verticalPadding: Dp = 9.dp,
    iconSize: Dp = 24.dp,
    titleStyle: TextStyle = TextStyle(
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    ),
    valueStyle: TextStyle = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    ),
    optionLeadingContent: @Composable (T) -> Unit = {},
    optionTestTag: ((T) -> String?)? = null,
    enabled: Boolean = true,
    emptyLabel: String = title,
    accentColor: Color? = null,
    optionEnabled: (T) -> Boolean = { true }
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableIntStateOf(0) }

    val canOpen = enabled && values.any(optionEnabled)
    val selectedIsAvailable = values.contains(selectedValue)
    val accent = accentColor ?: MaterialTheme.colorScheme.primary
    val anchorWidth = with(LocalDensity.current) { anchorWidthPx.toDp() }

    // A downloaded pack can disappear or become unavailable while this screen is visible.
    LaunchedEffect(canOpen) {
        if (!canOpen) expanded = false
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { anchorWidthPx = it.size.width }
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clickable(enabled = canOpen, role = Role.Button) { expanded = true }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(iconSize)
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = horizontalPadding)
                ) {
                    Text(text = title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (selectedIsAvailable) valueLabel(selectedValue) else emptyLabel,
                        style = valueStyle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        RankOfflinePopupMenu(
            expanded = expanded && canOpen,
            onDismissRequest = { expanded = false },
            modifier = if (anchorWidthPx > 0) Modifier.width(anchorWidth) else Modifier.fillMaxWidth()
        ) {
            values.forEach { value ->
                val isSelected = selectedIsAvailable && value == selectedValue
                RankOfflinePopupMenuItem(
                    label = valueLabel(value),
                    selected = isSelected,
                    enabled = optionEnabled(value),
                    accentColor = accent,
                    onClick = {
                        expanded = false
                        if (!isSelected) onValueSelected(value)
                    },
                    leadingContent = { optionLeadingContent(value) },
                    modifier = optionTestTag?.invoke(value)?.let { Modifier.testTag(it) } ?: Modifier
                )
            }
        }
    }
}

/** A shared popup surface for selectors and Search / Ranking sort controls. */
@Composable
fun RankOfflinePopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        content = content
    )
}

/** A themed menu option with an explicit selected state and an optional leading icon. */
@Composable
fun RankOfflinePopupMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    accentColor: Color? = null
) {
    val accent = accentColor ?: MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurface.let {
        if (enabled) it else it.copy(alpha = 0.38f)
    }
    val backgroundColor = if (selected) {
        lerp(MaterialTheme.colorScheme.surface, accent, 0.14f)
    } else {
        Color.Transparent
    }

    DropdownMenuItem(
        text = {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                leadingContent?.invoke()
                Text(
                    text = label,
                    color = textColor,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        enabled = enabled,
        onClick = onClick,
        trailingIcon = {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = if (enabled) accent else accent.copy(alpha = 0.38f)
                )
            }
        },
        modifier = modifier
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .semantics { this.selected = selected }
    )
}
