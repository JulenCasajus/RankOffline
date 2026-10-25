package org.rankoffline.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookIntegrationTest {
    private lateinit var database: AnimeDatabase
    private lateinit var repository: GenericMediaRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AnimeDatabase::class.java
        ).build()
        repository = GenericMediaRepository(database, now = { 100L })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun bookSearchUsesCanonicalAndAliasTitles() = runBlocking {
        val hobbit = book("OLTEST1W", "The Hobbit")
        addItem(hobbit)
        database.mediaCatalogDao().insertAliases(
            listOf(MediaAliasEntity(hobbit.id, "The Hobbit: There and Back Again", kind = "SUBTITLE"))
        )

        for (query in listOf("The Hobbit", "There and Back Again", "the hobbit")) {
            val results = search(query = query)
            assertEquals(1, results.size)
            assertEquals(hobbit.id, results.single().id)
        }
    }

    @Test
    fun bookSearchFiltersAreMediaScoped() = runBlocking {
        val dune = book("OL1W", "Foundation", year = 1965)
        val hobbit = book("OL2W", "The Hobbit", year = 1954)
        val manga = media("manga:mangadex:foundation", "manga", "mangadex", "foundation", "Foundation", 1965, "MANGA")
        val movie = media("movie:tmdb:1", "movie", "tmdb", "1", "Foundation", 1965, "MOVIE")
        val series = media("series:tmdb:1:season:1", "series", "tmdb", "1:season:1", "Foundation", 1965, "TV_SEASON")
        for (item in listOf(dune, hobbit, manga, movie, series)) addItem(item)
        database.mediaCatalogDao().insertGenres(
            listOf(
                MediaGenreEntity(dune.id, "Science Fiction"),
                MediaGenreEntity(hobbit.id, "Fantasy"),
                MediaGenreEntity(manga.id, "Science Fiction"),
                MediaGenreEntity(movie.id, "Science Fiction"),
                MediaGenreEntity(series.id, "Science Fiction")
            )
        )
        database.mediaCatalogDao().insertCredits(
            listOf(
                MediaCreditEntity(dune.id, "AUTHOR", "Frank Herbert"),
                MediaCreditEntity(hobbit.id, "AUTHOR", "J. R. R. Tolkien"),
                MediaCreditEntity(movie.id, "AUTHOR", "Leaked Movie Author"),
                MediaCreditEntity(dune.id, "DIRECTOR", "Leaked Director")
            )
        )

        assertEquals(listOf(dune.id), search(query = "Foundation").map { it.id })
        assertEquals(listOf(dune.id), search(filters = GenericMediaFilters(genre = "Science Fiction")).map { it.id })
        assertEquals(listOf(hobbit.id), search(filters = GenericMediaFilters(year = 1954)).map { it.id })
        assertEquals(
            listOf(hobbit.id),
            search(filters = GenericMediaFilters(creditName = "J. R. R. Tolkien")).map { it.id }
        )
        assertEquals(
            listOf(dune.id),
            search(
                filters = GenericMediaFilters(
                    genre = "Science Fiction",
                    year = 1965,
                    creditName = "Frank Herbert"
                )
            ).map { it.id }
        )

        val options = repository.filterOptions(MediaType.BOOK)
        assertEquals(listOf("Fantasy", "Science Fiction"), options.genres)
        assertEquals(listOf(1965, 1954), options.years)
        assertEquals(listOf("Frank Herbert", "J. R. R. Tolkien"), options.creditNames)
        assertTrue(options.demographics.isEmpty())
    }

    @Test
    fun bookRatingUsesBookCategoriesAndSimpleSavePreservesDetails() = runBlocking {
        val item = book("OLRATEDW", "Rated Book")
        addItem(item)
        val config = DefaultSettings.ratingConfig(MediaType.BOOK)
        val profile = DefaultSettings.profiles(MediaType.BOOK, config.categories).profiles.first()
        val draft = RatingDraft(
            categoryScores = mapOf("writing" to 8.0, "prose" to 9.0, "structure" to 7.0),
            additiveScore = 0.5,
            profileId = profile.id
        )

        repository.saveElaborateRating(item.id, draft, config, profileId = profile.id)

        val expected = ScoreCalculator.calculate(draft.categoryScores, draft.additiveScore, config)
        val elaborate = repository.rating(item.id)
        assertNotNull(elaborate)
        assertEquals(expected.qualityScore!!, elaborate!!.qualityScore!!, 0.0001)
        assertEquals(expected.finalScore!!, elaborate.finalScore!!, 0.0001)
        assertEquals(profile.id, elaborate.profileId)
        assertEquals(
            setOf("writing", "prose", "structure"),
            repository.ratingScores(item.id).map { it.categoryId }.toSet()
        )
        assertEquals(expected.finalScore, ranking(RankingSortOrder.FINAL_SCORE).single().finalScore)
        assertEquals(expected.qualityScore, ranking(RankingSortOrder.QUALITY_SCORE).single().qualityScore)
        assertEquals(0.5, ranking(RankingSortOrder.PERSONAL_TASTE).single().additiveScore!!, 0.0)
        assertEquals(9.0, ranking(RankingSortOrder.CATEGORY_SCORE, "prose").single().categoryScore!!, 0.0)

        repository.saveSimpleRating(item.id, repository.loadRating(item.id), 6.5, 6.5)

        val simple = repository.rating(item.id)!!
        assertEquals(6.5, simple.finalScore!!, 0.0)
        assertEquals(profile.id, simple.profileId)
        assertEquals(
            setOf("writing", "prose", "structure"),
            repository.ratingScores(item.id).map { it.categoryId }.toSet()
        )
    }

    @Test
    fun bookWithoutCoverAuthorOrYearRemainsSearchableRateableAndRanked() = runBlocking {
        val sparse = book("OLSPARSEW", "A Book Without Extras")
        addItem(sparse)

        val searchResult = search(merge = true).single() as MediaListEntry.Item
        assertNull(searchResult.value.remoteCoverRef)
        assertNull(searchResult.value.year)
        assertTrue(repository.filterOptions(MediaType.BOOK).creditNames.isEmpty())
        assertTrue(repository.filterOptions(MediaType.BOOK).years.isEmpty())

        repository.saveSimpleRating(sparse.id, RatingDraft(), 0.0, 0.0)
        val ranked = ranking(RankingSortOrder.FINAL_SCORE).single()

        assertEquals(sparse.id, ranked.id)
        assertEquals(0.0, ranked.finalScore!!, 0.0)
    }

    @Test
    fun missingOptionalBookCatalogIsUnavailableWithoutFallback() = runBlocking {
        // Other instrumentation classes exercise the real pack installer in the same target-app
        // sandbox. This test specifically verifies the no-files/no-bundled-assets branch.
        val context = IsolatedTestContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "missing-book-pack"
        )
        try {
            val manager = BundledMediaCatalogManager(
                context,
                database,
                bundledCatalogsAvailable = false
            )

            assertEquals(CatalogAvailability.UNAVAILABLE, manager.importOptional(MediaType.BOOK))
            assertTrue(search().isEmpty())
        } finally {
            context.delete()
        }
    }

    private suspend fun search(
        query: String = "",
        filters: GenericMediaFilters = GenericMediaFilters(),
        merge: Boolean = false
    ) = repository.search(
        MediaType.BOOK,
        query,
        CatalogSortOrder.TITLE_ASC,
        filters,
        merge
    )

    private suspend fun ranking(
        sortOrder: RankingSortOrder,
        categoryId: String? = null
    ) = repository.ranking(
        MediaType.BOOK,
        GenericMediaFilters(),
        sortOrder,
        categoryId,
        descending = true,
        mergeEnabled = false
    )

    private suspend fun addItem(item: MediaItemEntity) {
        repository.upsertItem(item)
    }

    private fun book(id: String, title: String, year: Int? = null) = media(
        id = "book:openlibrary:$id",
        mediaType = "book",
        provider = "openlibrary",
        providerId = id,
        title = title,
        year = year,
        format = "BOOK"
    )

    private fun media(
        id: String,
        mediaType: String,
        provider: String,
        providerId: String,
        title: String,
        year: Int?,
        format: String
    ) = MediaItemEntity(
        id = id,
        mediaType = mediaType,
        provider = provider,
        providerId = providerId,
        title = title,
        year = year,
        format = format
    )
}
