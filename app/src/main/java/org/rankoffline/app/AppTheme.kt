package org.rankoffline.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
internal fun RankOfflineTheme(
    palette: AppThemePalette,
    content: @Composable () -> Unit
) = RankOfflineTheme(palette.palette(), content)

@Composable
internal fun RankOfflineTheme(
    palette: AppPalette,
    content: @Composable () -> Unit
) {
    val colorScheme = remember(palette) { palette.toColorScheme() }
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.surface.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = colorScheme.background.luminance() >= 0.5f
                    isAppearanceLightNavigationBars = colorScheme.surface.luminance() >= 0.5f
                }
            }
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
