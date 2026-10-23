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
        repository = GenericMediaRepository(database, now = { 123L })
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

    @Test
    fun onlineDiscoveryUpsertPreservesRatingAndDownloadedCover() = runBlocking {
        val existing = item(MediaType.BOOK, "book:openlibrary:OL45804W", "OL45804W")
            .copy(localCoverPath = "/covers/book.jpg", coverResolutionState = "AVAILABLE")
        repository.upsertItem(existing)
        repository.saveSimpleRating(existing.id, RatingDraft(notes = "mine"), 0.0, 0.0)

        repository.upsertDiscoveredItem(
            MediaCatalogRecord(
                id = existing.id,
                mediaType = "book",
                provider = "openlibrary",
                providerId = "OL45804W",
                title = "Fantastic Mr. Fox",
                year = 1970,
                format = "BOOK",
                remoteCoverRef = "123",
                genres = listOf("Foxes"),
                credits = listOf(MediaCatalogCredit("AUTHOR", "Roald Dahl"))
            )
        )

        assertEquals(0.0, repository.rating(existing.id)!!.finalScore!!, 0.0)
        assertEquals("mine", repository.rating(existing.id)!!.notes)
        assertEquals("/covers/book.jpg", repository.item(existing.id)!!.localCoverPath)
        assertEquals("Fantastic Mr. Fox", repository.item(existing.id)!!.title)
    }

    @Test
    fun catalogRemovalPreservesExactPersonalDataAndReinstallReconnectsIt() = runBlocking {
        val movie = item(MediaType.MOVIE, "movie:tmdb:11", "11")
        repository.upsertItem(movie)
        repository.saveElaborateRating(
            movie.id,
            RatingDraft(
                categoryScores = mapOf("writing" to 9.0),
                additiveScore = 0.5,
                notes = "must survive",
                status = "Completed"
            ),
            singleCategoryConfig(),
            profileId = "narrative"
        )

        repository.deleteCatalogReferenceData(MediaType.MOVIE)

        assertNull(repository.item(movie.id))
        val detached = repository.rating(movie.id)!!
        assertEquals(9.5, detached.finalScore!!, 0.0)
        assertEquals(0.5, detached.additiveScore, 0.0)
        assertEquals("must survive", detached.notes)
        assertEquals("Completed", detached.status)
        assertEquals("narrative", detached.profileId)
        assertEquals(9.0, repository.ratingScores(movie.id).single().score!!, 0.0)

        repository.upsertItem(movie.copy(title = "Star Wars"))
        val reconnected = repository.item(movie.id)!!
        assertEquals("Star Wars", reconnected.title)
        assertEquals(9.5, reconnected.finalScore!!, 0.0)
    }

    @Test
    fun explicitPersonalDeletionIsCategoryScopedAndKeepsCatalog() = runBlocking {
        val movie = item(MediaType.MOVIE, "movie:tmdb:11", "11")
        val book = item(MediaType.BOOK, "book:openlibrary:OL1W", "OL1W")
        repository.upsertItems(listOf(movie, book))
        repository.saveSimpleRating(movie.id, RatingDraft(notes = "movie"), 7.0, 7.0)
        repository.saveSimpleRating(book.id, RatingDraft(notes = "book"), 9.0, 9.0)

        repository.deleteAllPersonalData(MediaType.MOVIE)

        assertNull(repository.rating(movie.id))
        assertEquals(9.0, repository.rating(book.id)!!.finalScore!!, 0.0)
        assertNotNull(repository.item(movie.id))
        assertNotNull(repository.item(book.id))
    }

    @Test
    fun animePersonalDeletionUsesPackIdentityRatherThanAnimeIdPrefix() = runBlocking {
        val anime = item(MediaType.ANIME, "mal:16498", "mal:16498")
        val movie = item(MediaType.MOVIE, "movie:tmdb:11", "11")
        repository.upsertItems(listOf(anime, movie))
        repository.saveSimpleRating(anime.id, RatingDraft(notes = "anime"), 9.0, 9.0)
        repository.saveSimpleRating(movie.id, RatingDraft(notes = "movie"), 7.0, 7.0)

        repository.deleteAllPersonalData(MediaType.ANIME)

        assertNull(repository.rating(anime.id))
        assertEquals("movie", repository.rating(movie.id)?.notes)
        assertNotNull(repository.item(anime.id))
    }

    @Test
    fun localSearchUsesStableOffsetPagesWithoutCrossMediaRows() = runBlocking {
        val manga = (0..200).map { index ->
            item(MediaType.MANGA, "manga:mangadex:${index.toString().padStart(3, '0')}", index.toString())
                .copy(title = "Same title")
        }
        repository.upsertItems(manga + item(MediaType.BOOK, "book:openlibrary:OL1W", "OL1W"))

        val first = repository.search(
            MediaType.MANGA, "", CatalogSortOrder.TITLE_ASC, GenericMediaFilters(),
            mergeEnabled = false, limit = 200, offset = 0
        ).map { it.id }
        val second = repository.search(
            MediaType.MANGA, "", CatalogSortOrder.TITLE_ASC, GenericMediaFilters(),
            mergeEnabled = false, limit = 50, offset = 200
        ).map { it.id }

        assertEquals(200, first.size)
        assertEquals(listOf("manga:mangadex:200"), second)
        assertEquals(201, (first + second).distinct().size)
    }

    @Test
    fun mergedRankingUsesOnlyRatedCandidatesAndPreservesCategorySemantics() = runBlocking {
        val first = item(MediaType.MOVIE, "movie:tmdb:101", "101").copy(title = "First")
        val second = item(MediaType.MOVIE, "movie:tmdb:102", "102").copy(title = "Second")
        val independent = item(MediaType.MOVIE, "movie:tmdb:103", "103").copy(title = "Independent")
        repository.upsertItems(listOf(first, second, independent))
        val groupRepository = GenericMediaGroupRepository(database, now = { 124L })
        val groupId = "movie:tmdb:collection:10"
        groupRepository.upsertGroup(
            MediaGroupEntity(
                id = groupId,
                mediaType = MediaType.MOVIE.persistedValue,
                groupType = "COLLECTION",
                provider = "tmdb",
                providerGroupId = "10",
                displayTitle = "Collection",
                representativeMediaId = first.id
            ),
            listOf(
                MediaGroupMemberEntity(groupId, first.id, 0),
                MediaGroupMemberEntity(groupId, second.id, 1)
            )
        )
        repository.saveElaborateRating(
            first.id,
            RatingDraft(categoryScores = mapOf("writing" to 9.0)),
            singleCategoryConfig()
        )
        repository.saveElaborateRating(
            second.id,
            RatingDraft(categoryScores = mapOf("writing" to 7.0)),
            singleCategoryConfig()
        )
        repository.saveElaborateRating(
            independent.id,
            RatingDraft(categoryScores = mapOf("writing" to 8.5)),
            singleCategoryConfig()
        )

        suspend fun categoryRanking(descending: Boolean) =
            repository.ranking(
                MediaType.MOVIE,
                GenericMediaFilters(),
                RankingSortOrder.CATEGORY_SCORE,
                categoryId = "writing",
                descending = descending,
                mergeEnabled = true
            )

        val derived = categoryRanking(true)
        assertEquals(listOf(independent.id, groupId), derived.map(MediaListEntry::id))
        assertEquals(8.0, (derived[1] as MediaListEntry.Group).categoryScore!!, 0.0)
        assertEquals(true, (derived[1] as MediaListEntry.Group).derived)
        assertEquals(listOf(groupId, independent.id), categoryRanking(false).map(MediaListEntry::id))

        groupRepository.saveElaborateGroupRating(
            groupId,
            RatingDraft(categoryScores = mapOf("writing" to 10.0)),
            singleCategoryConfig()
        )
        val explicit = categoryRanking(true)
        assertEquals(listOf(groupId, independent.id), explicit.map(MediaListEntry::id))
        assertEquals(10.0, (explicit.first() as MediaListEntry.Group).categoryScore!!, 0.0)
        assertEquals(false, (explicit.first() as MediaListEntry.Group).derived)
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
