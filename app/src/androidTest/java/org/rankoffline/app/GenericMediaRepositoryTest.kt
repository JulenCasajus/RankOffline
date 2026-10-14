package org.rankoffline.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenericMediaRepositoryTest {
    private lateinit var database: AnimeDatabase
    private lateinit var repository: GenericMediaRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AnimeDatabase::class.java
        ).build()
        repository = GenericMediaRepository(database) { 123L }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun simpleAndElaborateSavesShareOneCanonicalScore() = runBlocking {
        val item = item(MediaType.MANGA, "manga:mangadex:test-manga", "test-manga")
        repository.upsertItem(item)

        repository.saveSimpleRating(item.id, RatingDraft(notes = "simple"), 7.0, 7.0)
        assertEquals(7.0, repository.rating(item.id)!!.finalScore!!, 0.0)

        val config = singleCategoryConfig()
        repository.saveElaborateRating(
            item.id,
            RatingDraft(categoryScores = mapOf("writing" to 8.0), additiveScore = 0.6, notes = "elaborate"),
            config,
            profileId = "narrative"
        )
        val elaborate = repository.rating(item.id)!!
        assertEquals(8.6, elaborate.finalScore!!, 0.000001)
        assertEquals(8.0, elaborate.qualityScore!!, 0.0)
        assertEquals("narrative", elaborate.profileId)
        assertEquals(1, repository.ratingScores(item.id).size)

        val loaded = repository.loadRating(item.id)
        repository.saveSimpleRating(item.id, loaded.copy(notes = "simple again"), 6.5, 6.5)
        val lastSave = repository.rating(item.id)!!
        assertEquals(6.5, lastSave.finalScore!!, 0.0)
        assertEquals("simple again", lastSave.notes)
    }

    @Test
    fun simpleSavePreservesElaborateDetails() = runBlocking {
        val item = item(MediaType.BOOK, "book:openlibrary:OL45804W", "OL45804W")
        repository.upsertItem(item)
        repository.saveElaborateRating(
            item.id,
            RatingDraft(categoryScores = mapOf("writing" to 9.0, "characters" to null), additiveScore = 0.3),
            twoCategoryConfig(),
            profileId = "prose_style"
        )
        val elaborateDraft = repository.loadRating(item.id)

        repository.saveSimpleRating(item.id, elaborateDraft, 7.5, 7.8)

        val rating = repository.rating(item.id)!!
        val scores = repository.ratingScores(item.id).associateBy { it.categoryId }
        assertEquals(7.8, rating.finalScore!!, 0.0)
        assertEquals("prose_style", rating.profileId)
        assertEquals(9.0, scores.getValue("writing").score!!, 0.0)
        assertEquals(false, scores.getValue("characters").applicable)
    }

    @Test
    fun zeroRatingAndDeleteSemantics() = runBlocking {
        val item = item(MediaType.MOVIE, "movie:tmdb:550", "550").copy(localCoverPath = "/covers/550.jpg")
        repository.upsertItem(item)
        repository.saveElaborateRating(
            item.id,
            RatingDraft(categoryScores = mapOf("writing" to 5.0)),
            singleCategoryConfig(),
            profileId = "general"
        )
        val loaded = repository.loadRating(item.id)
        repository.saveSimpleRating(item.id, loaded, 0.0, 0.0)

        assertEquals(0.0, repository.rating(item.id)!!.finalScore!!, 0.0)
        repository.deleteRating(item.id)

        assertNull(repository.rating(item.id))
        assertEquals(emptyList<MediaRatingScoreEntity>(), repository.ratingScores(item.id))
        val preserved = repository.item(item.id)
        assertNotNull(preserved)
        assertEquals("/covers/550.jpg", preserved!!.localCoverPath)
    }

    @Test
    fun mediaCategoryCleanupIsIsolated() = runBlocking {
        val manga = item(MediaType.MANGA, "manga:mangadex:m", "m")
        val book = item(MediaType.BOOK, "book:openlibrary:b", "b")
        val movie = item(MediaType.MOVIE, "movie:tmdb:1", "1")
        repository.upsertItems(listOf(manga, book, movie))
        listOf(manga, book, movie).forEachIndexed { index, media ->
            repository.saveElaborateRating(
                media.id,
                RatingDraft(categoryScores = mapOf("writing" to (5.0 + index))),
                singleCategoryConfig(),
                profileId = "general"
            )
        }

        repository.deleteRatingCategory(MediaType.MANGA, "writing")

        assertEquals(0, repository.ratingScores(manga.id).size)
        assertEquals(1, repository.ratingScores(book.id).size)
        assertEquals(1, repository.ratingScores(movie.id).size)

        repository.deleteRatingScoresOutsideCategories(MediaType.BOOK, listOf("characters"))
        assertEquals(0, repository.ratingScores(book.id).size)
        assertEquals(1, repository.ratingScores(movie.id).size)
    }

    private fun item(type: MediaType, id: String, providerId: String) = MediaItemEntity(
        id = id,
        mediaType = type.persistedValue,
        provider = when (type) {
            MediaType.MANGA -> "mangadex"
            MediaType.BOOK -> "openlibrary"
            else -> "tmdb"
        },
        providerId = providerId,
        title = id
    )

    private fun singleCategoryConfig() = RatingSystemConfig(
        categories = listOf(RatingCategory("writing", "Writing", "", 100.0, "#000000", 0)),
        additiveBonus = AdditiveBonus("Taste", "", 1.0, "#000000")
    )

    private fun twoCategoryConfig() = RatingSystemConfig(
        categories = listOf(
            RatingCategory("writing", "Writing", "", 50.0, "#000000", 0),
            RatingCategory("characters", "Characters", "", 50.0, "#000000", 1)
        ),
        additiveBonus = AdditiveBonus("Taste", "", 1.0, "#000000")
    )
}
