package org.rankoffline.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Generic themed selector used across RankOffline.
 *
 * The opened menu always matches the width of the closed selector.
 * Its colors come entirely from MaterialTheme, so every RankOffline palette
 * and future custom theme works automatically.
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
    optionTestTag: ((T) -> String?)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableIntStateOf(0) }

    val density = LocalDensity.current

    val menuWidth = with(density) {
        anchorWidthPx.toDp()
    }

    val menuShape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                anchorWidthPx = coordinates.size.width
            }
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
            ),
            shape = menuShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clickable(
                    role = Role.Button,
                    onClick = { expanded = true }
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .padding(
                        horizontal = horizontalPadding,
                        vertical = verticalPadding
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(iconSize)
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = minHeight - (verticalPadding * 2))
                        .padding(horizontal = horizontalPadding),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        style = titleStyle
                    )

                    Text(
                        text = valueLabel(selectedValue),
                        style = valueStyle
                    )
                }

                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            },
            modifier = Modifier
                .then(
                    if (anchorWidthPx > 0) {
                        Modifier.width(menuWidth)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                ),
            shape = menuShape,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            )
        ) {
            values.forEach { value ->

                val selected = value == selectedValue

                val rowColor = if (selected) {
                    lerp(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.primary,
                        0.14f
                    )
                } else {
                    Color.Transparent
                }

                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            optionLeadingContent(value)

                            Text(
                                text = valueLabel(value),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                }
                            )
                        }
                    },
                    onClick = {
                        expanded = false

                        if (!selected) {
                            onValueSelected(value)
                        }
                    },
                    trailingIcon = {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    modifier = Modifier
                        .then(optionTestTag?.invoke(value)?.let(Modifier::testTag) ?: Modifier)
                        .padding(
                            horizontal = 6.dp,
                            vertical = 2.dp
                        )
                        .clip(RoundedCornerShape(10.dp))
                        .background(rowColor)
                )
            }
        }
    }
}
