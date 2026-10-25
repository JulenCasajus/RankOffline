package org.rankoffline.app

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CoverViewerUiTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AnimeDatabase
    private lateinit var image: File

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java).build()
        image = File(context.cacheDir, "viewer-${System.nanoTime()}.png")
        Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888).also { bitmap ->
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val first = movie("viewer-1")
        val second = movie("viewer-2")
        GenericMediaRepository(database).upsertItems(listOf(first, second))
        GenericMediaGroupRepository(database).upsertGroup(
            MediaGroupEntity(
                id = GROUP_ID,
                mediaType = MediaType.MOVIE.persistedValue,
                groupType = "COLLECTION",
                provider = "tmdb",
                providerGroupId = "viewer",
                displayTitle = "Viewer collection",
                representativeMediaId = first.id,
                memberCount = 2,
                localCoverPath = image.absolutePath,
                coverResolutionState = CoverResolutionState.AVAILABLE.name
            ),
            listOf(
                MediaGroupMemberEntity(GROUP_ID, first.id, 0),
                MediaGroupMemberEntity(GROUP_ID, second.id, 1)
            )
        )
    }

    @After
    fun tearDown() {
        image.delete()
        database.close()
    }

    @Test
    fun movieGroupViewerObservesGroupPathAndRemainsOpen() {
        var dismissed = false
        composeRule.setContent {
            RankOfflineTheme(AppThemePalette.MIDNIGHT_NEON) {
                CoverViewerDialog(
                    coverAnime = groupAnime(image.absolutePath),
                    displayAnime = groupAnime(image.absolutePath),
                    coverRepository = CoverBitmapCache(),
                    genericCoverRepository = GenericMediaCoverRepository(context, database),
                    genericGroupCoverRepository = GenericMediaGroupCoverRepository(context, database),
                    onDismiss = { dismissed = true },
                    onGroupDetails = null,
                    onDeleteConfirmed = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Viewer collection").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.cover_delete)).assertIsDisplayed()
        composeRule.runOnIdle { assertFalse(dismissed) }
    }

    @Test
    fun confirmedMissingPathShowsUnavailableStateInsteadOfDismissing() = runBlocking {
        database.mediaGroupDao().updateGroupCover(GROUP_ID, null, CoverResolutionState.UNKNOWN.name)
        var dismissed = false
        composeRule.setContent {
            RankOfflineTheme(AppThemePalette.MIDNIGHT_NEON) {
                CoverViewerDialog(
                    coverAnime = groupAnime(null),
                    displayAnime = groupAnime(null),
                    coverRepository = CoverBitmapCache(),
                    genericCoverRepository = GenericMediaCoverRepository(context, database),
                    genericGroupCoverRepository = GenericMediaGroupCoverRepository(context, database),
                    onDismiss = { dismissed = true },
                    onGroupDetails = null,
                    onDeleteConfirmed = {}
                )
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.message_cover_not_found))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle { assertFalse(dismissed) }
        Unit
    }

    private fun movie(id: String) = MediaItemEntity(
        id = "movie:tmdb:$id",
        mediaType = MediaType.MOVIE.persistedValue,
        provider = "tmdb",
        providerId = id,
        title = id
    )

    private fun groupAnime(path: String?) = Anime(
        id = GROUP_ID,
        title = "Viewer collection",
        mediaType = MediaType.MOVIE,
        localCoverPath = path,
        groupId = GROUP_ID,
        groupType = "COLLECTION",
        rootAnimeId = "movie:tmdb:viewer-1",
        memberCount = 2
    )

    private companion object {
        const val GROUP_ID = "movie:tmdb:collection:viewer"
    }
}
