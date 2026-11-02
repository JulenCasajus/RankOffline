package org.rankoffline.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val FloatingNavigationClearance = 104.dp
internal val LocalFloatingNavigationClearance = staticCompositionLocalOf { 0.dp }

@Composable
internal fun floatingNavigationContentPadding(
    top: Dp = 0.dp,
    bottom: Dp = 0.dp
): PaddingValues = PaddingValues(
    top = top,
    bottom = bottom + LocalFloatingNavigationClearance.current
)

@Composable
internal fun FloatingNavigationOverlay(
    activeTab: AppTab,
    onTab: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val background = MaterialTheme.colorScheme.background.copy(alpha = 1f)
    val selectedColor = MaterialTheme.colorScheme.primary.copy(alpha = 1f)
    val navigationItemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = bestContentColor(selectedColor),
        selectedTextColor = selectedColor,
        indicatorColor = selectedColor
    )

    Box(modifier.fillMaxWidth().height(136.dp)) {
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to background.copy(alpha = 0f),
                    0.35f to background.copy(alpha = 0.35f),
                    1f to background
                )
            )
        )
        Box(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    NavigationBarItem(
                        selected = activeTab == AppTab.SEARCH,
                        onClick = { onTab(AppTab.SEARCH) },
                        modifier = Modifier.offset(y = 3.dp),
                        icon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_search)) },
                        colors = navigationItemColors
                    )
                    NavigationBarItem(
                        selected = activeTab == AppTab.RANKING,
                        onClick = { onTab(AppTab.RANKING) },
                        modifier = Modifier.offset(y = 3.dp),
                        icon = { Icon(Icons.Default.EmojiEvents, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_ranking)) },
                        colors = navigationItemColors
                    )
                    NavigationBarItem(
                        selected = activeTab == AppTab.SETTINGS,
                        onClick = { onTab(AppTab.SETTINGS) },
                        modifier = Modifier.offset(y = 3.dp),
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_settings)) },
                        colors = navigationItemColors
                    )
                }
            }
        }
    }
}
