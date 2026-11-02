package org.rankoffline.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun CompactOnboardingLanguageSelector(
    selectedLanguage: SupportedLanguage,
    onLanguageSelected: (SupportedLanguage) -> Unit,
    layout: WelcomeLayoutMetrics,
    modifier: Modifier = Modifier
) {
    RankOfflineSelector(
        selectedValue = selectedLanguage,
        values = SupportedLanguage.entries,
        title = stringResource(R.string.language_title),
        icon = Icons.Default.Language,
        valueLabel = { language ->
            stringResource(language.displayNameResource)
        },
        optionTestTag = { language -> "onboarding_language_${language.languageTag}" },
        onValueSelected = onLanguageSelected,
        modifier = modifier.testTag("onboarding_language_selector"),
        minHeight = layout.selectorMinHeight,
        horizontalPadding = layout.selectorHorizontalPadding,
        verticalPadding = layout.selectorVerticalPadding,
        iconSize = layout.selectorIconSize,
        titleStyle = TextStyle(
            fontSize = layout.selectorTitleFontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        valueStyle = TextStyle(
            fontSize = layout.selectorValueFontSize,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    )
}


@Composable
internal fun CompactOnboardingThemeSelector(
    selectedTheme: AppThemePalette,
    onThemeSelected: (AppThemePalette) -> Unit,
    layout: WelcomeLayoutMetrics,
    modifier: Modifier = Modifier
) {
    RankOfflineSelector(
        selectedValue = selectedTheme,
        values = AppThemePalette.entries,
        title = stringResource(R.string.settings_theme_title),
        icon = Icons.Default.Palette,
        valueLabel = { palette ->
            stringResource(palette.displayNameResource)
        },
        onValueSelected = onThemeSelected,
        modifier = modifier.testTag("onboarding_theme_selector"),
        minHeight = layout.selectorMinHeight,
        horizontalPadding = layout.selectorHorizontalPadding,
        verticalPadding = layout.selectorVerticalPadding,
        iconSize = layout.selectorIconSize,
        titleStyle = TextStyle(
            fontSize = layout.selectorTitleFontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        valueStyle = TextStyle(
            fontSize = layout.selectorValueFontSize,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        ),
        optionLeadingContent = { palette ->
            PaletteSwatches(
                palette = palette,
                modifier = Modifier.padding(end = 12.dp)
            )
        }
    )
}


/*
 * Four miniature color circles make it much easier to recognise a theme
 * than displaying the name alone.
 */
@Composable
private fun PaletteSwatches(
    palette: AppThemePalette,
    modifier: Modifier = Modifier
) {
    val preview = palette.preview()

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PaletteSwatch(preview.background)
        PaletteSwatch(preview.surface)
        PaletteSwatch(preview.primary)
        PaletteSwatch(preview.secondary)
    }
}


@Composable
private fun PaletteSwatch(
    color: Color
) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}
