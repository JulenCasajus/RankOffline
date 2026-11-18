package org.rankoffline.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun WelcomeStyleDialog(
    settings: AppSettingsState,
    onLanguageSelected: (SupportedLanguage) -> Unit,
    onThemeSelected: (AppThemePalette) -> Unit,
    onComplete: (Boolean) -> Unit
) {
    var elaborateRating by rememberSaveable { mutableStateOf(settings.elaborateRatingEnabled) }
    WelcomeDialogBackground {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val layout = welcomeLayoutMetrics(maxHeight)
            Column(Modifier.fillMaxSize().padding(layout.outerPadding)) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.onboarding_welcome_title),
                        fontSize = layout.welcomeTitleFontSize,
                        lineHeight = layout.welcomeTitleLineHeight,
                        fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.onboarding_welcome_subtitle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = layout.welcomeSubtitleFontSize,
                        lineHeight = layout.welcomeSubtitleLineHeight,
                        modifier = Modifier.padding(top = layout.subtitleTopPadding))

                    CompactOnboardingLanguageSelector(settings.language, onLanguageSelected, layout,
                        modifier = Modifier.padding(top = layout.selectorTopPadding))

                    Text(stringResource(R.string.settings_rating_title),
                        fontSize = layout.sectionTitleFontSize,
                        lineHeight = layout.sectionTitleLineHeight,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = layout.sectionTopPadding, bottom = 6.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().toggleable(
                            value = elaborateRating, role = Role.Switch,
                            onValueChange = { elaborateRating = it }
                        ).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.onboarding_rating_elaborate),
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text(stringResource(if (elaborateRating)
                                    R.string.onboarding_rating_elaborate_description
                                else R.string.onboarding_rating_simple_description),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = layout.helperFontSize,
                                    lineHeight = layout.helperLineHeight)
                            }
                            Switch(checked = elaborateRating, onCheckedChange = null,
                                modifier = Modifier.testTag("onboarding_elaborate_rating"))
                        }
                    }

                    Text(stringResource(R.string.onboarding_covers_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = layout.helperFontSize,
                        lineHeight = layout.helperLineHeight,
                        modifier = Modifier.padding(top = layout.sectionTopPadding))

                    CompactOnboardingThemeSelector(settings.themePalette, onThemeSelected, layout,
                        modifier = Modifier.padding(top = layout.themeTopPadding,
                            bottom = layout.themeBottomPadding))

                    Text(stringResource(R.string.onboarding_offline_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = layout.helperFontSize,
                        lineHeight = layout.helperLineHeight)
                }
                Button(onClick = { onComplete(elaborateRating) },
                    modifier = Modifier.fillMaxWidth().padding(top = layout.buttonTopPadding)
                        .testTag("onboarding_start")) {
                    Text(stringResource(R.string.onboarding_start), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
