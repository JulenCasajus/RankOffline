package org.rankoffline.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RatingProfileSelectorUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun twentyFiveProfilesRemainReachableAtTwoHundredPercentFontScale() {
        val profiles = (1..25).map { index ->
            RatingProfile("profile_$index", "Profile $index", mapOf("writing" to 100.0))
        }
        var selected = profiles.first()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                RankOfflineTheme(AppThemePalette.MIDNIGHT_NEON) {
                    RatingProfileSelector(
                        profiles = profiles,
                        selected = selected,
                        onSelected = { selected = it }
                    )
                }
            }
        }

        composeRule.onNodeWithTag("rating_profile_selector").performClick()
        composeRule.onNodeWithTag("rating_profile_list").performScrollToIndex(25)
        composeRule.onNodeWithTag("rating_profile_profile_25")
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle { assertEquals("profile_25", selected.id) }
    }
}
