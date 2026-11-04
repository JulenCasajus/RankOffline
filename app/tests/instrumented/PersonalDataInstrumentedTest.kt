package org.rankoffline.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalDataInstrumentedTest {
    private lateinit var database: RankOfflineDatabase

    @Before fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun closeDatabase() = database.close()

    @Test fun metadataOnlyEditPreservesEveryScoreAndProfileField() = runBlocking {
        val original = MediaRatingEntity(1, "item", 0.75, 8.25, 9.0,
            "old note", "watching", 10, "weighted")
        val score = MediaRatingScoreEntity(1, "item", "writing", 8.25, true)
        database.mediaDao().saveRating(original, listOf(score))

        RatingRepository(database) { 20 }.saveMetadata(
            CatalogEntryKey(PackId(1), "item", CatalogEntryKind.ITEM), "new note", "done"
        )

        val edited = requireNotNull(database.mediaDao().rating(1, "item"))
        assertEquals(original.copy(notes = "new note", status = "done", updatedAt = 20), edited)
        assertEquals(listOf(score), database.mediaDao().ratingScores(1, "item"))
    }

    @Test fun partialReplaceNeverDeletesAnotherPacksPersonalData() = runBlocking {
        val dao = database.personalDataDao()
        dao.upsertRatings(listOf(rating(1, "old", 1), rating(2, "keep", 2)))

        dao.restore(PersonalRestoreData(
            ratings = listOf(rating(1, "new", 3)),
            ratingScores = emptyList(), groupRatings = emptyList(), groupRatingScores = emptyList()
        ), replace = true, packId = 1)

        assertEquals(setOf(1L to "new", 2L to "keep"),
            dao.allRatings().map { it.packId to it.mediaId }.toSet())
    }

    @Test fun mergeAcceptsOnlyNewerIncomingRowsAndTheirScores() = runBlocking {
        val dao = database.personalDataDao()
        dao.upsertRatings(listOf(rating(1, "newer-local", 20), rating(1, "older-local", 10)))
        dao.upsertRatingScores(listOf(
            MediaRatingScoreEntity(1, "newer-local", "quality", 9.0, true),
            MediaRatingScoreEntity(1, "older-local", "quality", 4.0, true)
        ))

        dao.restore(PersonalRestoreData(
            ratings = listOf(rating(1, "newer-local", 5), rating(1, "older-local", 30)),
            ratingScores = listOf(
                MediaRatingScoreEntity(1, "newer-local", "quality", 1.0, true),
                MediaRatingScoreEntity(1, "older-local", "quality", 8.0, true)
            ), groupRatings = emptyList(), groupRatingScores = emptyList()
        ), replace = false)

        assertEquals(20, database.mediaDao().rating(1, "newer-local")?.updatedAt)
        assertEquals(9.0, database.mediaDao().ratingScores(1, "newer-local").single().score)
        assertEquals(30, database.mediaDao().rating(1, "older-local")?.updatedAt)
        assertEquals(8.0, database.mediaDao().ratingScores(1, "older-local").single().score)
    }

    @Test fun invalidRestoreRollsBackBeforeChangingStoredData() = runBlocking {
        val dao = database.personalDataDao()
        val original = rating(1, "safe", 10)
        dao.upsertRatings(listOf(original))
        val invalid = PersonalRestoreData(
            ratings = listOf(rating(1, "duplicate", 20), rating(1, "duplicate", 21)),
            ratingScores = emptyList(), groupRatings = emptyList(), groupRatingScores = emptyList()
        )

        val error = runCatching { dao.restore(invalid, replace = true) }.exceptionOrNull()

        assertNotEquals(null, error)
        assertEquals(listOf(original), dao.allRatings())
    }

    private fun rating(packId: Long, id: String, updatedAt: Long) =
        MediaRatingEntity(packId, id, 0.0, 7.0, 7.0, "", "", updatedAt, "general")
}
