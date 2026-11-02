package org.rankoffline.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight

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
