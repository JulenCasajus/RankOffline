package org.rankoffline.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    @Test
    fun categoryStepSelectsOnlyReadyPacksAndClearsSelectionAfterLastRemoval() {
        var settings by mutableStateOf(
            AppSettingsState(
                activeMediaType = MediaType.ANIME,
                hasCompletedWelcome = true,
                hasCompletedOnboarding = false,
                settingsLoaded = true
            )
        )
        var packs by mutableStateOf<Map<MediaType, CategoryPackState>>(emptyMap())
        composeRule.setContent {
            RankOfflineTheme(AppThemePalette.MIDNIGHT_NEON) {
                FirstRunOnboarding(
                    settings = settings,
                    categoryPacks = packs,
                    downloadProgress = null,
                    onWelcomeComplete = { _, _ -> },
                    onLanguageSelected = {},
                    onThemeSelected = {},
                    onDownloadCategory = {},
                    onRemoveCategory = {},
                    onMediaTypeSelected = {},
                    onOnboardingComplete = {}
                )
            }
        }

        composeRule.onAllNodesWithTag(
            "category_pack_selected_animes",
            useUnmergedTree = true
        ).assertCountEquals(0)

        composeRule.runOnIdle {
            settings = settings.withActiveMediaType(MediaType.MANGA)
            packs = mapOf(MediaType.MANGA to readyPack(MediaType.MANGA))
        }
        composeRule.onNodeWithTag(
            "category_pack_selected_mangas",
            useUnmergedTree = true
        ).fetchSemanticsNode()

        composeRule.runOnIdle {
            packs = mapOf(MediaType.MANGA to unavailablePack(MediaType.MANGA))
        }
        composeRule.onAllNodesWithTag(
            "category_pack_selected_mangas",
            useUnmergedTree = true
        ).assertCountEquals(0)
    }

    @Test
    fun categoryStepCanFinishWithoutDownloadingAnyPack() {
        var completed = false
        composeRule.setContent {
            RankOfflineTheme(AppThemePalette.MIDNIGHT_NEON) {
                FirstRunOnboarding(
                    settings = AppSettingsState(
                        hasCompletedWelcome = true,
                        hasCompletedOnboarding = false,
                        settingsLoaded = true
                    ),
                    categoryPacks = emptyMap(),
                    downloadProgress = null,
                    onWelcomeComplete = { _, _ -> },
                    onLanguageSelected = {},
                    onThemeSelected = {},
                    onDownloadCategory = {},
                    onRemoveCategory = {},
                    onMediaTypeSelected = {},
                    onOnboardingComplete = { completed = true }
                )
            }
        }

        composeRule.onNodeWithTag("onboarding_done")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(true, completed) }
    }

    private fun readyPack(mediaType: MediaType) = CategoryPackState(
        mediaType = mediaType,
        origin = CategoryPackOrigin.DOWNLOADED,
        catalogBytes = 1,
        downloadedCoverBytes = 0,
        reclaimableBytes = 1,
        runtimeStatus = CategoryPackRuntimeStatus.READY
    )

    private fun unavailablePack(mediaType: MediaType) = CategoryPackState(
        mediaType = mediaType,
        origin = CategoryPackOrigin.UNAVAILABLE,
        catalogBytes = 0,
        downloadedCoverBytes = 0,
        reclaimableBytes = 0,
        runtimeStatus = CategoryPackRuntimeStatus.FILES_MISSING
    )
}
