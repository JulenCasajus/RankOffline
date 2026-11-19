package org.rankoffline.app

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackPersonalPurgeInstrumentedTest {
    @Test fun uninstallRetainsRatingsButPurgeRemovesOnlySelectedPackAcrossDatabaseRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "pack-personal-purge-test.db"
        context.deleteDatabase(name)
        var database = Room.databaseBuilder(context, RankOfflineDatabase::class.java, name).build()
        try {
            val catalog = database.mediaCatalogDao()
            catalog.insertNewItems(listOf(MediaItemEntity(1, "item", "Item")))
            database.mediaGroupDao().insertNewGroups(listOf(MediaGroupEntity(1, "group", "relations", "Group")))
            val personal = database.personalDataDao()
            personal.upsertRatings(listOf(
                MediaRatingEntity(1, "item", 0.0, 8.0, 8.0, "note", "", 1),
                MediaRatingEntity(2, "item", 0.0, 9.0, 9.0, "keep", "", 1)
            ))
            personal.upsertRatingScores(listOf(
                MediaRatingScoreEntity(1, "item", "quality", 8.0, true),
                MediaRatingScoreEntity(2, "item", "quality", 9.0, true)
            ))
            personal.upsertGroupRatings(listOf(
                MediaGroupRatingEntity(1, "group", 0.0, 7.0, 7.0, "note", "", 1),
                MediaGroupRatingEntity(2, "group", 0.0, 6.0, 6.0, "keep", "", 1)
            ))
            personal.upsertGroupRatingScores(listOf(
                MediaGroupRatingScoreEntity(1, "group", "quality", 7.0, true),
                MediaGroupRatingScoreEntity(2, "group", "quality", 6.0, true)
            ))

            catalog.deleteCatalogReferenceData(1) // Ordinary uninstall.
            catalog.insertNewItems(listOf(MediaItemEntity(1, "item", "Reinstalled")))
            database.mediaGroupDao().insertNewGroups(listOf(MediaGroupEntity(1, "group", "relations", "Reinstalled")))
            assertEquals(8.0, database.mediaDao().rating(1, "item")?.finalScore!!, 0.0)
            assertEquals(7.0, database.mediaGroupDao().rating(1, "group")?.finalScore!!, 0.0)

            database.withTransaction { personal.deletePersonalData(1) }
            catalog.deleteCatalogReferenceData(1)
            catalog.insertNewItems(listOf(MediaItemEntity(1, "item", "Reinstalled again")))
            database.mediaGroupDao().insertNewGroups(listOf(MediaGroupEntity(1, "group", "relations", "Reinstalled again")))
            database.close()
            database = Room.databaseBuilder(context, RankOfflineDatabase::class.java, name).build()
            val remaining = database.personalDataDao()
            assertEquals(setOf(2L), remaining.allRatings().map { it.packId }.toSet())
            assertEquals(setOf(2L), remaining.allRatingScores().map { it.packId }.toSet())
            assertEquals(setOf(2L), remaining.allGroupRatings().map { it.packId }.toSet())
            assertEquals(setOf(2L), remaining.allGroupRatingScores().map { it.packId }.toSet())
        } finally {
            database.close()
            assertTrue(context.deleteDatabase(name))
        }
    }

    @Test fun interruptedPersonalDeleteRollsBackBothRatingKindsAndScores() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java).build()
        try {
            val personal = database.personalDataDao()
            personal.upsertRatings(listOf(MediaRatingEntity(1, "item", 0.0, 8.0, 8.0, "", "", 1)))
            personal.upsertRatingScores(listOf(MediaRatingScoreEntity(1, "item", "quality", 8.0, true)))
            personal.upsertGroupRatings(listOf(MediaGroupRatingEntity(1, "group", 0.0, 7.0, 7.0, "", "", 1)))
            personal.upsertGroupRatingScores(listOf(MediaGroupRatingScoreEntity(1, "group", "quality", 7.0, true)))
            runCatching { database.withTransaction {
                personal.deletePersonalData(1)
                error("simulated interruption before commit")
            } }
            assertEquals(1, personal.allRatings().size)
            assertEquals(1, personal.allRatingScores().size)
            assertEquals(1, personal.allGroupRatings().size)
            assertEquals(1, personal.allGroupRatingScores().size)
        } finally { database.close() }
    }
}
