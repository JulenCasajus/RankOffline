package org.rankoffline.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseIdentityInstrumentedTest {
    private lateinit var database: RankOfflineDatabase

    @Before fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun closeDatabase() = database.close()

    @Test fun identicalItemIdsRemainIndependentAcrossPacks() = runBlocking {
        database.mediaCatalogDao().insertNewItems(listOf(
            MediaItemEntity(1, "shared", "Pack one"),
            MediaItemEntity(2, "shared", "Pack two")
        ))

        assertEquals("Pack one", database.mediaDao().item(1, "shared")?.title)
        assertEquals("Pack two", database.mediaDao().item(2, "shared")?.title)
    }

    @Test fun deletingCatalogNeverDeletesDetachedPersonalRatings() = runBlocking {
        database.mediaCatalogDao().insertNewItems(listOf(MediaItemEntity(1, "rated", "Rated")))
        val rating = MediaRatingEntity(1, "rated", 0.5, 8.0, 8.5, "note", "done", 7)
        database.mediaDao().saveRating(rating,
            listOf(MediaRatingScoreEntity(1, "rated", "writing", 8.0, true)))

        database.mediaCatalogDao().deleteCatalogReferenceData(1)

        assertNull(database.mediaDao().item(1, "rated"))
        assertEquals(rating, database.mediaDao().rating(1, "rated"))
        assertEquals(1, database.mediaDao().ratingScores(1, "rated").size)
    }

    @Test fun catalogRefreshPreservesDownloadedCoverState() = runBlocking {
        val dao = database.mediaCatalogDao()
        dao.insertNewItems(listOf(MediaItemEntity(1, "cover", "Old", remoteCoverRef = "old",
            localCoverPath = "covers/cover.webp", coverResolutionState = "READY")))

        assertEquals(1, dao.updateCatalogFields(listOf(MediaCatalogItemFields(
            1, "cover", "New", null, null, 10.0, 3, "new"
        ))))

        val refreshed = database.mediaDao().item(1, "cover")
        assertEquals("New", refreshed?.title)
        assertEquals("new", refreshed?.remoteCoverRef)
        assertEquals("covers/cover.webp", refreshed?.localCoverPath)
        assertEquals("READY", refreshed?.coverResolutionState)
    }

    @Test fun coverCompareAndSetUsesNullSafeExpectedValues() = runBlocking {
        val catalog = database.mediaCatalogDao()
        val media = database.mediaDao()
        catalog.insertNewItems(listOf(MediaItemEntity(1, "cover", "Cover")))

        assertEquals(1, media.compareAndSetCover(1, "cover", null, null,
            "covers/cover.webp", "READY"))
        assertEquals(0, media.compareAndSetCover(1, "cover", null, null,
            "covers/replaced.webp", "READY"))
        assertEquals(1, media.compareAndSetCover(1, "cover", null, "covers/cover.webp",
            null, "NOT_FOUND"))
    }

    @Test fun groupRelationsCannotCrossPackBoundaries() = runBlocking {
        database.mediaCatalogDao().insertNewItems(listOf(MediaItemEntity(2, "item", "Other pack")))
        database.mediaGroupDao().insertNewGroups(listOf(
            MediaGroupEntity(1, "group", "relations", "Group")
        ))

        val error = runCatching {
            database.mediaGroupDao().insertMemberships(listOf(
                MediaGroupMemberEntity(1, "group", "item", 0)
            ))
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals(0, database.mediaGroupDao().memberCount(1, "group"))
    }
}
