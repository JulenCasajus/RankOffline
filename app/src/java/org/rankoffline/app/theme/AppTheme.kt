package org.rankoffline.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Applies one of the immutable, built-in RankOffline palettes. */
@Composable
internal fun RankOfflineTheme(
    palette: AppThemePalette,
    content: @Composable () -> Unit
) = RankOfflineTheme(palette.palette(), content)

/**
 * Applies any app palette, including user overrides and custom palettes.
 *
 * The Activity is responsible for enabling edge-to-edge. The content layout must
 * handle WindowInsets. Do not set statusBarColor/navigationBarColor here: those
 * window color APIs do not control the bars on modern target SDKs.
 */
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
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars =
                        bestContentColor(colorScheme.background) == Color.Black
                    isAppearanceLightNavigationBars =
                        bestContentColor(colorScheme.surface) == Color.Black
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
