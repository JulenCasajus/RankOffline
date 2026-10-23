package org.rankoffline.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RatingPersistenceIntegrationTest {
    @Test
    fun itemAndGroupRatingsSurviveRepositoryAndDatabaseRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "rating-persistence-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        var database = Room.databaseBuilder(context, AnimeDatabase::class.java, databaseName).build()
        val firstItem = MediaItemEntity(
            id = "movie:tmdb:persistence:1",
            mediaType = MediaType.MOVIE.persistedValue,
            provider = "tmdb",
            providerId = "persistence-1",
            title = "Persistent one"
        )
        val secondItem = firstItem.copy(
            id = "movie:tmdb:persistence:2",
            providerId = "persistence-2",
            title = "Persistent two"
        )
        val groupId = "movie:tmdb:collection:persistence"
        GenericMediaRepository(database).upsertItems(listOf(firstItem, secondItem))
        GenericMediaGroupRepository(database).upsertGroup(
            MediaGroupEntity(
                id = groupId,
                mediaType = MediaType.MOVIE.persistedValue,
                groupType = "COLLECTION",
                provider = "tmdb",
                providerGroupId = "persistence",
                displayTitle = "Persistent collection",
                representativeMediaId = firstItem.id
            ),
            listOf(
                MediaGroupMemberEntity(groupId, firstItem.id, 0),
                MediaGroupMemberEntity(groupId, secondItem.id, 1)
            )
        )
        GenericMediaRepository(database).saveSimpleRating(
            firstItem.id,
            RatingDraft(notes = "item persisted"),
            qualityScore = 8.0,
            finalScore = 8.0
        )
        GenericMediaGroupRepository(database).saveSimpleGroupRating(
            groupId,
            RatingDraft(notes = "group persisted"),
            qualityScore = 9.0,
            finalScore = 9.0
        )
        database.close()

        database = Room.databaseBuilder(context, AnimeDatabase::class.java, databaseName).build()
        try {
            val itemRating = GenericMediaRepository(database).loadRating(firstItem.id)
            val groupRating = GenericMediaGroupRepository(database)
                .loadGroupRating(groupId, fromMembers = false)
            assertEquals(8.0, requireNotNull(itemRating.finalScore), 0.0)
            assertEquals("item persisted", itemRating.notes)
            assertEquals(9.0, requireNotNull(groupRating.finalScore), 0.0)
            assertEquals("group persisted", groupRating.notes)
            assertNotNull(GenericMediaGroupRepository(database).groupDetail(groupId)?.explicitRating)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
