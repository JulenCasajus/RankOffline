package org.rankoffline.app

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun FirstRunOnboarding(
    settings: AppSettingsState,
    categoryPacks: Map<MediaType, CategoryPackState>,
    downloadProgress: CategoryDownloadProgress?,
    onWelcomeComplete: (showImages: Boolean, elaborateRating: Boolean) -> Unit,
    onLanguageSelected: (SupportedLanguage) -> Unit,
    onThemeSelected: (AppThemePalette) -> Unit,
    onDownloadCategory: (MediaType) -> Unit,
    onCancelCategoryCoverDownload: (MediaType) -> Unit = {},
    onRemoveCategory: (MediaType) -> Unit,
    onMediaTypeSelected: (MediaType) -> Unit,
    onOnboardingComplete: () -> Unit
) {
    if (!settings.hasCompletedWelcome) {
        WelcomeStyleDialog(
            settings = settings,
            onLanguageSelected = onLanguageSelected,
            onThemeSelected = onThemeSelected,
            onComplete = onWelcomeComplete
        )
    } else if (!settings.hasCompletedOnboarding) {
        CategoryOnboardingDialog(
            categoryPacks = categoryPacks,
            downloadProgress = downloadProgress,
            onDownloadCategory = onDownloadCategory,
            onCancelCategoryCoverDownload = onCancelCategoryCoverDownload,
            onRemoveCategory = onRemoveCategory,
            selectedMediaType = onboardingSelectedMediaType(
                activeMediaType = settings.activeMediaType,
                categoryPacks = categoryPacks
            ),
            onMediaTypeSelected = onMediaTypeSelected,
            onComplete = onOnboardingComplete
        )
    }
}


@Composable
internal fun WelcomeStyleDialog(
    settings: AppSettingsState,
    onLanguageSelected: (SupportedLanguage) -> Unit,
    onThemeSelected: (AppThemePalette) -> Unit,
    onComplete: (Boolean, Boolean) -> Unit
) {
    var showImages by rememberSaveable { mutableStateOf(settings.showImages) }
    var elaborateRating by rememberSaveable {
        mutableStateOf(settings.elaborateRatingEnabled)
    }

    WelcomeDialogBackground {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val layout = welcomeLayoutMetrics(maxHeight)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(layout.outerPadding)
            ) {
                /*
                 * The content area is scrollable only as a safety net for
                 * accessibility font scaling, long translations or unusually
                 * short displays. At normal font scale the continuous sizing
                 * below is designed to fit without scrolling.
                 */
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_welcome_title),
                        fontSize = layout.welcomeTitleFontSize,
                        lineHeight = layout.welcomeTitleLineHeight,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = stringResource(R.string.onboarding_welcome_subtitle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = layout.welcomeSubtitleFontSize,
                        lineHeight = layout.welcomeSubtitleLineHeight,
                        modifier = Modifier.padding(top = layout.subtitleTopPadding)
                    )

                    CompactOnboardingLanguageSelector(
                        selectedLanguage = settings.language,
                        onLanguageSelected = onLanguageSelected,
                        layout = layout,
                        modifier = Modifier.padding(top = layout.selectorTopPadding)
                    )

                    Text(
                        text = stringResource(R.string.onboarding_choose_style),
                        fontSize = layout.sectionTitleFontSize,
                        lineHeight = layout.sectionTitleLineHeight,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = layout.sectionTopPadding)
                    )

                    Text(
                        text = stringResource(R.string.onboarding_change_later),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = layout.helperFontSize,
                        lineHeight = layout.helperLineHeight
                    )

                    OnboardingOptionRow(
                        modifier = Modifier.padding(top = layout.optionRowSpacing),
                        height = layout.optionHeight,
                        first = { optionModifier ->
                            OnboardingOptionCard(
                                title = stringResource(R.string.onboarding_minimal),
                                preview = R.drawable.onboarding_style_minimal,
                                selected = !showImages,
                                onClick = { showImages = false },
                                layout = layout,
                                modifier = optionModifier.testTag(
                                    "onboarding_style_minimal"
                                )
                            )
                        },
                        second = { optionModifier ->
                            OnboardingOptionCard(
                                title = stringResource(R.string.onboarding_with_images),
                                preview = R.drawable.onboarding_style_images,
                                selected = showImages,
                                onClick = { showImages = true },
                                layout = layout,
                                modifier = optionModifier.testTag(
                                    "onboarding_style_images"
                                )
                            )
                        }
                    )

                    OnboardingOptionRow(
                        modifier = Modifier.padding(top = layout.optionRowSpacing),
                        height = layout.optionHeight,
                        first = { optionModifier ->
                            OnboardingOptionCard(
                                title = stringResource(R.string.onboarding_rating_simple),
                                preview = R.drawable.onboarding_rating_simple,
                                selected = !elaborateRating,
                                onClick = { elaborateRating = false },
                                layout = layout,
                                modifier = optionModifier.testTag(
                                    "onboarding_rating_simple"
                                )
                            )
                        },
                        second = { optionModifier ->
                            OnboardingOptionCard(
                                title = stringResource(R.string.onboarding_rating_elaborated),
                                preview = R.drawable.onboarding_rating_elaborated,
                                selected = elaborateRating,
                                onClick = { elaborateRating = true },
                                layout = layout,
                                modifier = optionModifier.testTag(
                                    "onboarding_rating_elaborated"
                                )
                            )
                        }
                    )

                    CompactOnboardingThemeSelector(
                        selectedTheme = settings.themePalette,
                        onThemeSelected = onThemeSelected,
                        layout = layout,
                        modifier = Modifier.padding(
                            top = layout.themeTopPadding,
                            bottom = layout.themeBottomPadding
                        )
                    )
                }

                Button(
                    onClick = {
                        onComplete(
                            showImages,
                            elaborateRating
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = layout.buttonTopPadding)
                        .testTag("onboarding_start")
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_start),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}


/*
 * Responsive metrics for the welcome screen.
 *
 * The layout scales continuously between a compact 600 dp reference and
 * a tall 820 dp reference. Taller screens keep the calibrated 236 dp card
 * height instead of growing forever; very short screens keep minimum
 * readable sizes and retain scrolling as a fallback.
 */
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


@Composable
private fun CompactOnboardingLanguageSelector(
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
private fun CompactOnboardingThemeSelector(
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


@Composable
private fun CategoryOnboardingDialog(
    categoryPacks: Map<MediaType, CategoryPackState>,
    downloadProgress: CategoryDownloadProgress?,
    onDownloadCategory: (MediaType) -> Unit,
    onCancelCategoryCoverDownload: (MediaType) -> Unit,
    onRemoveCategory: (MediaType) -> Unit,
    selectedMediaType: MediaType?,
    onMediaTypeSelected: (MediaType) -> Unit,
    onComplete: () -> Unit
) {
    OnboardingDialogBackground {
        Text(
            text = stringResource(R.string.onboarding_categories_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = stringResource(R.string.onboarding_categories_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(
                top = 6.dp,
                bottom = 12.dp
            )
        )

        CategoryManagementContent(
            categoryPacks = categoryPacks,
            downloadProgress = downloadProgress,
            onDownloadCategory = onDownloadCategory,
            onCancelCategoryCoverDownload = onCancelCategoryCoverDownload,
            onRemoveCategory = onRemoveCategory,
            selectedMediaType = selectedMediaType,
            onMediaTypeSelected = onMediaTypeSelected
        )

        Button(
            onClick = onComplete,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
                .testTag("onboarding_done")
        ) {
            Text(
                text = stringResource(R.string.onboarding_done),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** The persisted active pack is only a valid onboarding selection once its catalog is READY. */
internal fun onboardingSelectedMediaType(
    activeMediaType: MediaType,
    categoryPacks: Map<MediaType, CategoryPackState>
): MediaType? = activeMediaType.takeIf { categoryPacks[it]?.available == true }


@Composable
private fun OnboardingDialogBackground(
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(26.dp),
            tonalElevation = 3.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                content = content
            )
        }
    }
}


@Composable
private fun WelcomeDialogBackground(
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(26.dp),
            tonalElevation = 3.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f),
            content = content
        )
    }
}


@Composable
private fun OnboardingOptionRow(
    modifier: Modifier = Modifier,
    height: Dp,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        first(
            Modifier
                .weight(1f)
                .height(height)
        )

        second(
            Modifier
                .weight(1f)
                .height(height)
        )
    }
}


@Composable
private fun OnboardingOptionCard(
    title: String,
    @DrawableRes preview: Int,
    selected: Boolean,
    onClick: () -> Unit,
    layout: WelcomeLayoutMetrics,
    modifier: Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                lerp(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.primary,
                    0.18f
                )
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            }
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onClick
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(layout.optionCardPadding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(preview),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(12.dp))
                )

                Spacer(
                    modifier = Modifier.height(layout.optionImageTextSpacing)
                )

                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = layout.optionTitleFontSize,
                    lineHeight = layout.optionTitleLineHeight,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(layout.selectedCheckSize)
                )
            }
        }
    }
}


@Composable
fun CategoryManagementContent(
    categoryPacks: Map<MediaType, CategoryPackState>,
    @Suppress("UNUSED_PARAMETER")
    downloadProgress: CategoryDownloadProgress? = null,
    onDownloadCategory: (MediaType) -> Unit = {},
    onCancelCategoryCoverDownload: (MediaType) -> Unit = {},
    onRemoveCategory: (MediaType) -> Unit = {},
    selectedMediaType: MediaType? = null,
    onMediaTypeSelected: ((MediaType) -> Unit)? = null
) {
    val context = LocalContext.current

    val ordered = listOf(
        MediaType.ANIME,
        MediaType.BOOK,
        MediaType.MANGA,
        MediaType.MOVIE,
        MediaType.SERIES
    )

    val states = ordered.map { mediaType ->
        categoryPacks[mediaType]
            ?: CategoryPackState(
                mediaType,
                CategoryPackOrigin.UNAVAILABLE,
                0L,
                0L,
                0L
            )
    }

    val installedPackCount = states.count(CategoryPackState::available)
    val installedBytes = states
        .filter(CategoryPackState::available)
        .sumOf(CategoryPackState::installedBytes)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Text(
                text = stringResource(
                    R.string.categories_storage_title
                ),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = pluralStringResource(
                    R.plurals.categories_packs_installed,
                    installedPackCount,
                    installedPackCount
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 3.dp)
            )

            Text(
                text = stringResource(
                    R.string.categories_installed_size,
                    Formatter.formatFileSize(context, installedBytes)
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }

    var pendingRemoval by remember {
        mutableStateOf<MediaType?>(null)
    }

    states.forEach { state ->
        CategoryPackRow(
            state = state,
            selected = state.mediaType == selectedMediaType,
            onSelect = {
                if (state.available) {
                    onMediaTypeSelected?.invoke(
                        state.mediaType
                    )
                }
            },
            onDownload = {
                onDownloadCategory(state.mediaType)
            },
            onCancelCoverDownload = {
                onCancelCategoryCoverDownload(state.mediaType)
            },
            onRemove = {
                pendingRemoval =
                    state.mediaType
            }
        )
    }

    pendingRemoval?.let { mediaType ->
        val state =
            categoryPacks.getValue(mediaType)

        CategoryConfirmationDialog(
            title = stringResource(
                R.string.category_purge_title,
                stringResource(mediaType.labelResource)
            ),
            body = stringResource(
                R.string.category_purge_confirmation,
                stringResource(mediaType.labelResource),
                Formatter.formatFileSize(
                    context,
                    state.reclaimableBytes
                )
            ),
            confirmText = stringResource(
                R.string.category_purge_action
            ),
            destructive = true,
            onDismiss = {
                pendingRemoval = null
            },
            onConfirm = {
                pendingRemoval = null
                onRemoveCategory(mediaType)
            }
        )
    }
}


@Composable
private fun CategoryPackRow(
    state: CategoryPackState,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancelCoverDownload: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val progress = state.installProgress

    Card(
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (selected) {
            BorderStroke(
                2.dp,
                state.mediaType.accentColor
            )
        } else {
            null
        },
        shape = RoundedCornerShape(15.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable(
                enabled = state.available,
                role = Role.RadioButton,
                onClick = onSelect
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 14.dp,
                    vertical = 13.dp
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = mediaScreenTitle(
                            "RankOffline",
                            stringResource(
                                state.mediaType.labelResource
                            ),
                            state.mediaType.accentColor
                        ),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp
                    )

                    Text(
                        text = when (progress?.phase) {
                            CategoryPackInstallPhase.WAITING_DOWNLOAD ->
                                stringResource(R.string.category_phase_waiting_download)
                            CategoryPackInstallPhase.DOWNLOADING ->
                                stringResource(R.string.category_phase_downloading)
                            CategoryPackInstallPhase.VERIFYING ->
                                stringResource(R.string.category_phase_verifying)
                            CategoryPackInstallPhase.INSTALLING ->
                                stringResource(R.string.category_phase_installing)
                            CategoryPackInstallPhase.WAITING_IMPORT ->
                                stringResource(R.string.category_phase_waiting_import)
                            CategoryPackInstallPhase.IMPORTING_CATALOG ->
                                stringResource(R.string.category_phase_importing_catalog)
                            CategoryPackInstallPhase.IMPORTING_GROUPS ->
                                stringResource(R.string.category_phase_importing_groups)
                            CategoryPackInstallPhase.WAITING_COVERS ->
                                stringResource(R.string.category_phase_waiting_covers)
                            CategoryPackInstallPhase.DOWNLOADING_COVERS ->
                                stringResource(
                                    if (progress.coverRetrying) {
                                        R.string.category_phase_retrying_covers
                                    } else {
                                        R.string.category_phase_downloading_covers
                                    },
                                    progress.coverProcessed,
                                    progress.coverTarget
                                )
                            CategoryPackInstallPhase.FINALIZING ->
                                stringResource(R.string.category_phase_finalizing)
                            null -> if (state.runtimeStatus == CategoryPackRuntimeStatus.PURGING) {
                                stringResource(R.string.category_deleting)
                            } else if (state.runtimeStatus == CategoryPackRuntimeStatus.ERROR) {
                                stringResource(R.string.category_pack_runtime_error)
                            } else when (state.origin) {
                            CategoryPackOrigin.BUNDLED ->
                                stringResource(
                                    R.string.category_included_in_app
                                )

                            CategoryPackOrigin.DOWNLOADED ->
                                stringResource(
                                    R.string.category_downloaded
                                )

                            CategoryPackOrigin.UNAVAILABLE ->
                                stringResource(
                                    R.string.category_not_installed
                                )
                            }
                        },
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )

                    if (progress == null && !state.available) {
                        Text(
                            text = Formatter.formatFileSize(context, state.finalStorageBytes),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }

                if (progress == null && state.available) {
                    Text(
                        text = Formatter.formatFileSize(context, state.finalStorageBytes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }

                if (selected) {
                    Icon(
                        imageVector =
                            Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint =
                            state.mediaType.accentColor,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(22.dp)
                            .testTag("category_pack_selected_${state.mediaType.toPackId().value}")
                    )
                }

                Spacer(
                    modifier = Modifier.size(8.dp)
                )

                if (progress == null) {
                    if (state.canRetry) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_retry),
                            tint = state.mediaType.accentColor,
                            modifier = Modifier
                                .size(40.dp)
                                .clickable(role = Role.Button, onClick = onDownload)
                                .padding(8.dp)
                        )
                    }
                    val icon = when {
                        state.canDelete -> Icons.Default.Delete
                        state.canDownload -> Icons.Default.CloudDownload
                        else -> Icons.Default.Lock
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = when {
                            state.canDelete -> stringResource(R.string.category_remove_catalog)
                            state.canDownload -> stringResource(R.string.category_download)
                            else -> stringResource(R.string.category_download_unavailable)
                        },
                        tint = if (state.canDelete || state.canDownload) {
                            state.mediaType.accentColor
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(
                                enabled = state.canDelete || state.canDownload,
                                role = Role.Button,
                                onClick = if (state.canDelete) onRemove else onDownload
                            )
                            .padding(8.dp)
                    )
                } else if (
                    progress.phase == CategoryPackInstallPhase.WAITING_COVERS ||
                    progress.phase == CategoryPackInstallPhase.DOWNLOADING_COVERS
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = state.mediaType.accentColor,
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(role = Role.Button, onClick = onCancelCoverDownload)
                            .padding(8.dp)
                    )
                }
            }

            progress?.let { installProgress ->
                if (installProgress.phase == CategoryPackInstallPhase.DOWNLOADING) {
                    Text(
                        text = stringResource(
                            R.string.categories_download_progress,
                            Formatter.formatFileSize(
                                context,
                                installProgress.downloadedBytes
                            ),
                            Formatter.formatFileSize(context, installProgress.totalDownloadBytes)
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    LinearProgressIndicator(
                        progress = { installProgress.fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 5.dp),
                        color = state.mediaType.accentColor
                    )
                } else if (installProgress.phase == CategoryPackInstallPhase.DOWNLOADING_COVERS) {
                    LinearProgressIndicator(
                        progress = { installProgress.coverFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        color = state.mediaType.accentColor
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        color = state.mediaType.accentColor
                    )
                }
            }
            if (progress == null && state.runtimeStatus == CategoryPackRuntimeStatus.PURGING) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    color = state.mediaType.accentColor
                )
            }

            if (
                state.origin ==
                CategoryPackOrigin.UNAVAILABLE &&
                !state.downloadConfigured
            ) {
                Text(
                    text = stringResource(
                        R.string.category_download_not_configured
                    ),
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier =
                        Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}


@Composable
private fun CategoryConfirmationDialog(
    title: String,
    body: String,
    confirmText: String,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title)
        },
        text = {
            Text(body)
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text(
                    stringResource(
                        R.string.action_cancel
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm
            ) {
                Text(
                    text = confirmText,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    )
}
