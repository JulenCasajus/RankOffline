package org.rankoffline.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MediaScreenTitleUiTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun zeroPacksShowsOnlyDownloadAction() {
        var downloads = 0
        composeRule.setContent {
            RankOfflineTheme(AppThemePalette.MIDNIGHT_NEON) {
                MediaScreenTitle(
                    title = "RankOffline",
                    activeMediaType = MediaType.ANIME,
                    onMediaTypeSelected = {},
                    availableMediaTypes = emptySet(),
                    onDownloadPack = { downloads += 1 },
                    noPacksInstalled = true
                )
            }
        }

        composeRule.onNodeWithTag("media_screen_title").performClick()
        MediaType.entries.forEach { mediaType ->
            composeRule.onAllNodesWithTag("media_pack_${mediaType.persistedValue}").assertCountEquals(0)
        }
        composeRule.onNodeWithTag("media_download_pack").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, downloads) }
    }

    @Test
    fun onlyReadyPacksAreSelectableAndDownloadActionIsNeverSelected() {
        var selected: MediaType? = null
        var downloads = 0
        val ready = setOf(MediaType.ANIME, MediaType.MANGA)
        composeRule.setContent {
            RankOfflineTheme(AppThemePalette.MIDNIGHT_NEON) {
                MediaScreenTitle(
                    title = "RankOffline",
                    activeMediaType = MediaType.ANIME,
                    onMediaTypeSelected = { selected = it },
                    availableMediaTypes = ready,
                    onDownloadPack = { downloads += 1 }
                )
            }
        }

        composeRule.onNodeWithTag("media_screen_title").performClick()
        composeRule.onNodeWithTag("media_pack_anime").assertIsDisplayed()
        composeRule.onNodeWithTag("media_pack_manga").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(MediaType.MANGA, selected) }

        composeRule.onNodeWithTag("media_screen_title").performClick()
        listOf(MediaType.MOVIE, MediaType.SERIES, MediaType.BOOK).forEach { mediaType ->
            composeRule.onAllNodesWithTag("media_pack_${mediaType.persistedValue}").assertCountEquals(0)
        }
        composeRule.onNodeWithText(context.getString(R.string.download_pack)).assertIsDisplayed()
        composeRule.onNodeWithTag("media_download_pack").performClick()
        composeRule.runOnIdle {
            assertEquals(1, downloads)
            assertTrue(selected != null)
        }
    }
}
