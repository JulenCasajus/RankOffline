package org.rankoffline.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Smooth scaling between compact and tall phones, with scroll fallback on small screens. */
internal data class WelcomeLayoutMetrics(
    val outerPadding: Dp,
    val optionHeight: Dp,
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
    val optionTitleFontSize: TextUnit,
    val optionTitleLineHeight: TextUnit,
    val optionCardPadding: Dp,
    val optionImageTextSpacing: Dp,
    val selectedCheckSize: Dp,
    val subtitleTopPadding: Dp,
    val selectorTopPadding: Dp,
    val sectionTopPadding: Dp,
    val optionRowSpacing: Dp,
    val themeTopPadding: Dp,
    val themeBottomPadding: Dp,
    val buttonTopPadding: Dp
)

internal fun welcomeLayoutMetrics(maxHeight: Dp): WelcomeLayoutMetrics {
    /*
     * 600 dp: compact-phone baseline.
     * 820 dp: tall-phone reference.
     *
     * Every value between them is interpolated instead of jumping between
     * arbitrary breakpoints.
     */
    val scale = ((maxHeight.value - 600f) / (820f - 600f))
        .coerceIn(0f, 1f)

    fun scaledDp(compact: Float, tall: Float): Dp =
        (compact + (tall - compact) * scale).dp

    fun scaledSp(compact: Float, tall: Float): TextUnit =
        (compact + (tall - compact) * scale).sp

    /*
     * Non-card content also becomes slightly more compact on short screens.
     * This leaves the remaining height to the two card rows.
     *
     * The cards use 90% of the previous calibrated height. This trims the real-device
     * layout without shrinking text or interactive controls below accessible sizes.
     */
    val reservedNonCardHeight = scaledDp(
        compact = 300f,
        tall = 348f
    )

    val optionHeight = (((maxHeight - reservedNonCardHeight) / 2f) * 0.9f)
        .coerceIn(120.dp, 212.4.dp)

    return WelcomeLayoutMetrics(
        outerPadding = scaledDp(11f, 14f),
        optionHeight = optionHeight,

        selectorMinHeight = scaledDp(48f, 54f),
        selectorHorizontalPadding = scaledDp(12f, 14f),
        selectorVerticalPadding = scaledDp(7f, 9f),
        selectorIconSize = scaledDp(22f, 24f),
        selectorTitleFontSize = scaledSp(12f, 14f),
        selectorValueFontSize = scaledSp(14f, 16f),

        welcomeTitleFontSize = scaledSp(24f, 26f),
        welcomeTitleLineHeight = scaledSp(28f, 30f),
        welcomeSubtitleFontSize = scaledSp(12f, 14f),
        welcomeSubtitleLineHeight = scaledSp(16f, 18f),

        sectionTitleFontSize = scaledSp(20f, 22f),
        sectionTitleLineHeight = scaledSp(22f, 24f),
        helperFontSize = scaledSp(12f, 14f),
        helperLineHeight = scaledSp(14f, 16f),

        optionTitleFontSize = scaledSp(13f, 14f),
        optionTitleLineHeight = scaledSp(16f, 17f),

        optionCardPadding = scaledDp(6f, 9f),
        optionImageTextSpacing = scaledDp(4f, 7f),
        selectedCheckSize = scaledDp(20f, 22f),

        subtitleTopPadding = scaledDp(2f, 4f),
        selectorTopPadding = scaledDp(5f, 9f),
        sectionTopPadding = scaledDp(9f, 12f),
        optionRowSpacing = scaledDp(5f, 9f),
        themeTopPadding = scaledDp(7f, 10f),
        themeBottomPadding = scaledDp(3f, 5f),
        buttonTopPadding = scaledDp(5f, 9f)
    )
}
