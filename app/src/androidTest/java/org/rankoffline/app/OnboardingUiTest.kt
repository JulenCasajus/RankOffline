package org.rankoffline.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnboardingUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun welcomeShowsLanguageAndAllFourOptionsWithoutScrolling() {
        composeRule.setContent {
            RankOfflineTheme(AppThemePalette.MIDNIGHT_NEON) {
                WelcomeStyleDialog(
                    settings = AppSettingsState(settingsLoaded = true),
                    onLanguageSelected = {},
                    onThemeSelected = {},
                    onComplete = { _, _ -> }
                )
            }
        }

        composeRule.onNodeWithTag("onboarding_language_selector").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_style_minimal").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_style_images").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_rating_simple").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_rating_elaborated").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_start").assertIsDisplayed()
    }

    @Test
    fun languageSelectorListsAllSupportedLanguagesAndUsesSharedSelectionCallback() {
        var selected: SupportedLanguage? = null
        composeRule.setContent {
            RankOfflineTheme(AppThemePalette.MIDNIGHT_NEON) {
                WelcomeStyleDialog(
                    settings = AppSettingsState(settingsLoaded = true),
                    onLanguageSelected = { selected = it },
                    onThemeSelected = {},
                    onComplete = { _, _ -> }
                )
            }
        }

        composeRule.onNodeWithTag("onboarding_language_selector").performClick()
        SupportedLanguage.entries.forEach { language ->
            composeRule.onNodeWithTag("onboarding_language_${language.languageTag}").fetchSemanticsNode()
        }
        composeRule.onNodeWithTag("onboarding_language_es").performClick()
        composeRule.runOnIdle { assertEquals(SupportedLanguage.SPANISH, selected) }
    }

    @Test
    fun selectingOptionsUsesTheExistingCompletionValues() {
        var completed: Pair<Boolean, Boolean>? = null
        composeRule.setContent {
            RankOfflineTheme(AppThemePalette.MIDNIGHT_NEON) {
                WelcomeStyleDialog(
                    settings = AppSettingsState(showImages = false, settingsLoaded = true),
                    onLanguageSelected = {},
                    onThemeSelected = {},
                    onComplete = { showImages, elaborate -> completed = showImages to elaborate }
                )
            }
        }

        composeRule.onNodeWithTag("onboarding_style_images").performClick()
        composeRule.onNodeWithTag("onboarding_rating_elaborated").performClick()
        composeRule.onNodeWithTag("onboarding_start").performClick()
        composeRule.runOnIdle { assertEquals(true to true, completed) }
    }

    @Test
    fun confirmingWelcomeContinuesToTheExistingCategoryStep() {
        val categoryStepTitle = InstrumentationRegistry.getInstrumentation()
            .targetContext.getString(R.string.onboarding_categories_title)
        composeRule.setContent {
            var settings by remember {
                mutableStateOf(AppSettingsState(settingsLoaded = true))
            }
            RankOfflineTheme(AppThemePalette.MIDNIGHT_NEON) {
                FirstRunOnboarding(
                    settings = settings,
                    categoryPacks = emptyMap(),
                    downloadProgress = null,
                    onWelcomeComplete = { showImages, elaborate ->
                        settings = settings.copy(
                            showImages = showImages,
                            mediaSettings = settings.mediaSettings.mapValues { (_, mediaSettings) ->
                                mediaSettings.copy(elaborateRatingEnabled = elaborate)
                            },
                            hasCompletedWelcome = true
                        )
                    },
                    onLanguageSelected = {},
                    onThemeSelected = {},
                    onDownloadCategory = {},
                    onRemoveCategory = {},
                    onMediaTypeSelected = {},
                    onOnboardingComplete = {}
                )
            }
        }

        composeRule.onNodeWithTag("onboarding_start").performClick()
        composeRule.onNodeWithText(categoryStepTitle).assertIsDisplayed()
    }
}
