package org.rankoffline.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Compact spacing; scrolling remains available for large fonts and short screens. */
internal data class WelcomeLayoutMetrics(
    val outerPadding: Dp,
    val selectorMinHeight: Dp,
    val selectorHorizontalPadding: Dp,
    val selectorVerticalPadding: Dp,
    val selectorIconSize: Dp,
    val selectorTitleFontSize: TextUnit,
    val selectorValueFontSize: TextUnit,
    val welcomeTitleFontSize: TextUnit,
    val welcomeTitleLineHeight: TextUnit,
    val welcomeSubtitleFontSize: TextUnit,
    val welcomeSubtitleLineHeight: TextUnit,
    val sectionTitleFontSize: TextUnit,
    val sectionTitleLineHeight: TextUnit,
    val helperFontSize: TextUnit,
    val helperLineHeight: TextUnit,
    val subtitleTopPadding: Dp,
    val selectorTopPadding: Dp,
    val sectionTopPadding: Dp,
    val themeTopPadding: Dp,
    val themeBottomPadding: Dp,
    val buttonTopPadding: Dp
)

internal fun welcomeLayoutMetrics(maxHeight: Dp): WelcomeLayoutMetrics {
    val scale = ((maxHeight.value - 600f) / 220f).coerceIn(0f, 1f)
    fun dpBetween(compact: Float, tall: Float) = (compact + (tall - compact) * scale).dp
    fun spBetween(compact: Float, tall: Float) = (compact + (tall - compact) * scale).sp
    return WelcomeLayoutMetrics(
        outerPadding = dpBetween(12f, 16f),
        selectorMinHeight = dpBetween(48f, 54f),
        selectorHorizontalPadding = dpBetween(12f, 14f),
        selectorVerticalPadding = dpBetween(7f, 9f),
        selectorIconSize = dpBetween(22f, 24f),
        selectorTitleFontSize = spBetween(12f, 14f),
        selectorValueFontSize = spBetween(14f, 16f),
        welcomeTitleFontSize = spBetween(24f, 26f),
        welcomeTitleLineHeight = spBetween(28f, 30f),
        welcomeSubtitleFontSize = spBetween(12f, 14f),
        welcomeSubtitleLineHeight = spBetween(16f, 18f),
        sectionTitleFontSize = spBetween(16f, 18f),
        sectionTitleLineHeight = spBetween(20f, 22f),
        helperFontSize = spBetween(12f, 14f),
        helperLineHeight = spBetween(16f, 18f),
        subtitleTopPadding = dpBetween(4f, 6f),
        selectorTopPadding = dpBetween(8f, 12f),
        sectionTopPadding = dpBetween(12f, 16f),
        themeTopPadding = dpBetween(10f, 14f),
        themeBottomPadding = dpBetween(4f, 8f),
        buttonTopPadding = dpBetween(8f, 12f)
    )
}
