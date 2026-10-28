package org.rankoffline.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalDataDaoTest {
    private lateinit var database: AnimeDatabase
    private lateinit var dao: PersonalDataDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AnimeDatabase::class.java
        ).build()
        dao = database.personalDataDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun mergeKeepsNewerLocalRatingAndItsDetailedScores() = runBlocking {
        dao.restore(restoreData(updatedAt = 20L, score = 9.0, notes = "local"), replace = true)

        dao.restore(restoreData(updatedAt = 10L, score = 2.0, notes = "older backup"), replace = false)

        assertEquals("local", dao.allRatings().single().notes)
        assertEquals(9.0, dao.allRatingScores().single().score!!, 0.0)
    }

    @Test
    fun mergeReplacesMainRatingAndScoresOnlyWhenIncomingIsNewer() = runBlocking {
        dao.restore(restoreData(updatedAt = 10L, score = 2.0, notes = "local"), replace = true)

        dao.restore(restoreData(updatedAt = 20L, score = 9.0, notes = "new backup"), replace = false)

        assertEquals("new backup", dao.allRatings().single().notes)
        assertEquals(9.0, dao.allRatingScores().single().score!!, 0.0)
    }

    @Test
    fun groupMergeUsesTheSameTimestampAndDetailedScorePolicy() = runBlocking {
        dao.restore(groupRestoreData(updatedAt = 20L, score = 9.0, notes = "local"), replace = true)

        dao.restore(groupRestoreData(updatedAt = 20L, score = 4.0, notes = "tie"), replace = false)
        assertEquals("local", dao.allGroupRatings().single().notes)
        assertEquals(9.0, dao.allGroupRatingScores().single().score!!, 0.0)

        dao.restore(groupRestoreData(updatedAt = 30L, score = 8.0, notes = "newer"), replace = false)
        assertEquals("newer", dao.allGroupRatings().single().notes)
        assertEquals(8.0, dao.allGroupRatingScores().single().score!!, 0.0)
    }

    @Test
    fun replaceSupportsPersonalDataForAnUninstalledPack() = runBlocking {
        val detached = restoreData(updatedAt = 10L, score = 7.0, notes = "detached")

        dao.restore(detached, replace = true)

        assertEquals(OfficialPackIds.Books.value, dao.allRatings().single().packId)
        assertEquals("detached", dao.allRatings().single().notes)
    }

    @Test
    fun replaceOnlyDeletesRatingsOwnedByTheBackupScope() = runBlocking {
        dao.restore(restoreData(updatedAt = 10L, score = 7.0, notes = "book"), replace = true)
        val anime = PersonalRestoreData(
            ratings = listOf(
                MediaRatingEntity(
                    "mal:1", 0.0, 8.0, 8.0, "anime", "completed", 20L, "general",
                    OfficialPackIds.Anime.value
                )
            ),
            ratingScores = emptyList(),
            groupRatings = emptyList(),
            groupRatingScores = emptyList(),
            scopePackIds = setOf(OfficialPackIds.Anime.value)
        )

        dao.restore(anime, replace = true)

        assertEquals(
            setOf(OfficialPackIds.Anime.value, OfficialPackIds.Books.value),
            dao.allRatings().map(MediaRatingEntity::packId).toSet()
        )
    }

    @Test
    fun mergeBatchesScoreReplacementBeyondSqliteBindLimit() = runBlocking {
        fun data(updatedAt: Long, score: Double) = (0 until 1_001).let { indexes ->
            PersonalRestoreData(
                ratings = indexes.map { index ->
                    MediaRatingEntity(
                        "book:$index", 0.0, score, score, "note", "completed",
                        updatedAt, "general", OfficialPackIds.Books.value
                    )
                },
                ratingScores = indexes.map { index ->
                    MediaRatingScoreEntity(
                        "book:$index", "writing", score, true, OfficialPackIds.Books.value
                    )
                },
                groupRatings = emptyList(),
                groupRatingScores = emptyList()
            )
        }
        dao.restore(data(1L, 2.0), replace = true)

        dao.restore(data(2L, 9.0), replace = false)

        assertEquals(1_001, dao.allRatings().size)
        assertEquals(1_001, dao.allRatingScores().size)
        assertEquals(setOf(9.0), dao.allRatingScores().mapNotNull { it.score }.toSet())
    }

    @Test
    fun logicalStorageIsPackScopedAndUpdatesFromRoom() = runBlocking {
        val settings = MutableStateFlow(AppSettingsState(settingsLoaded = true))
        val storage = PackStorageRepository(database, settings)
        dao.restore(restoreData(updatedAt = 10L, score = 7.0, notes = "bytes"), replace = true)

        val books = storage.observePersonalStorage(MediaType.BOOK).first {
            it.logicalDatabaseBytes > 0L
        }
        val anime = storage.observePersonalStorage(MediaType.ANIME).first()

        assert(books.logicalDatabaseBytes > 0L)
        assertEquals(0L, anime.logicalDatabaseBytes)
        assert(books.logicalPreferencesBytes > 0L)
    }

    private fun restoreData(updatedAt: Long, score: Double, notes: String) = PersonalRestoreData(
        ratings = listOf(
            MediaRatingEntity(
                mediaId = ITEM_ID,
                additiveScore = 0.0,
                qualityScore = score,
                finalScore = score,
                notes = notes,
                status = "completed",
                updatedAt = updatedAt,
                profileId = "general",
                packId = OfficialPackIds.Books.value
            )
        ),
        ratingScores = listOf(
            MediaRatingScoreEntity(
                mediaId = ITEM_ID,
                categoryId = "writing",
                score = score,
                applicable = true,
                packId = OfficialPackIds.Books.value
            )
        ),
        groupRatings = emptyList(),
        groupRatingScores = emptyList()
    )

    private fun groupRestoreData(updatedAt: Long, score: Double, notes: String) = PersonalRestoreData(
        ratings = emptyList(),
        ratingScores = emptyList(),
        groupRatings = listOf(
            MediaGroupRatingEntity(
                groupId = GROUP_ID,
                additiveScore = 0.0,
                qualityScore = score,
                finalScore = score,
                notes = notes,
                status = "completed",
                updatedAt = updatedAt,
                profileId = "general",
                packId = OfficialPackIds.Series.value
            )
        ),
        groupRatingScores = listOf(
            MediaGroupRatingScoreEntity(
                groupId = GROUP_ID,
                categoryId = "writing",
                score = score,
                applicable = true,
                packId = OfficialPackIds.Series.value
            )
        )
    )

    companion object {
        private const val ITEM_ID = "book:openlibrary:OL1W"
        private const val GROUP_ID = "series:tmdb:1"
    }
}
