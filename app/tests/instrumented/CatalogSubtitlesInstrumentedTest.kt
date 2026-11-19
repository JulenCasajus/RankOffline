package org.rankoffline.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking

class CatalogSubtitlesInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactAttributeAndGroupCountHaveIndependentActions() {
        val field = TestFixtures.manifest(11, merge = true).fields.first { it.id == "genre" }
        val entry = MediaListEntry.Group(
            value = MediaGroup(PackId(11), "group", "Group", "relations",
                attributes = mapOf("genre" to listOf("drama", "comedy", "action")
                    .map(CatalogAttributeValue::Text))),
            finalScore = null, qualityScore = null, additiveScore = null,
            categoryScore = null, memberCount = 3, ratedCount = 0, derived = true
        )
        var groupClicks = 0
        compose.setContent { MaterialTheme {
            CatalogSubtitles(entry, listOf(field), onGroupDetails = { groupClicks++ })
        } }
        compose.onNodeWithText("Genre (3)").performClick()
        compose.onNodeWithText("action").assertIsDisplayed()
        assertEquals(0, groupClicks)
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("3 Entries").performClick()
        assertEquals(1, groupClicks)
    }

    @Test fun coverViewerUsesTheSameIndependentSubtitleActions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java).build()
        try {
            database.mediaGroupDao().insertNewGroups(listOf(
                MediaGroupEntity(11, "group", "relations", "Group")
            ))
            val manifest = TestFixtures.manifest(11, merge = true)
            val field = manifest.fields.first { it.id == "genre" }
            val entry = MediaListEntry.Group(
                value = MediaGroup(PackId(11), "group", "Group", "relations",
                    attributes = mapOf("genre" to listOf("drama", "comedy", "action")
                        .map(CatalogAttributeValue::Text))),
                finalScore = null, qualityScore = null, additiveScore = null,
                categoryScore = null, memberCount = 3, ratedCount = 0, derived = true
            )
            val covers = CoverRepository(context, database, { manifest },
                beforeCoverMutation = { _, _ -> })
            val cache = CoverBitmapCache()
            var groupClicks = 0
            compose.setContent { MaterialTheme {
                CoverViewerDialog(entry, AppSettingsState(), covers, cache,
                    onDismiss = {}, onDeleteConfirmed = { _, done -> done(false) },
                    subtitleFields = listOf(field), onGroupDetails = { groupClicks++ })
            } }
            compose.onNodeWithText("Genre (3)").performClick()
            compose.onNodeWithText("action").assertIsDisplayed()
            assertEquals(0, groupClicks)
            compose.onNodeWithText("OK").performClick()
            compose.onNodeWithText("3 Entries").performClick()
            assertEquals(1, groupClicks)
        } finally { database.close() }
    }
}
