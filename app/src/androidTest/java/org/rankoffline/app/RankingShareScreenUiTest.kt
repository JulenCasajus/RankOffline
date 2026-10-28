package org.rankoffline.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class RankingShareScreenUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun toggleDoesNotResetPagerAndContinueUsesVisibleVariant() {
        var exported: RankingShareRenderRequest? = null
        val data = RankingShareData(
            scope = ViewingScope.Pack(MediaType.ANIME),
            items = (1..10).map { index ->
                RankingShareItem(
                    packId = OfficialPackIds.Anime,
                    canonicalId = "anime-$index",
                    title = "Title $index",
                    rating = "${10 - index / 10.0}",
                    coverPath = null
                )
            },
            loading = false
        )
        composeRule.setContent {
            RankOfflineTheme(AppThemePalette.MIDNIGHT_NEON) {
                RankingShareScreen(
                    data = data,
                    readyMediaTypes = setOf(MediaType.ANIME),
                    allAvailable = false,
                    initialShowImages = { true },
                    exporting = false,
                    onScopeSelected = {},
                    onContinue = { exported = it },
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithTag("ranking_share_show_images").assertIsOn()
        composeRule.onNodeWithTag("ranking_share_pager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ranking_share_page_1").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        )

        composeRule.onNodeWithTag("ranking_share_show_images").performClick().assertIsOff()
        composeRule.onNodeWithTag("ranking_share_page_1").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        )
        composeRule.onNodeWithTag("ranking_share_continue").performClick()

        composeRule.runOnIdle {
            assertEquals(RankingShareTemplate.TOP_TEN, exported?.template)
            assertFalse(requireNotNull(exported).showImages)
        }
    }
}
