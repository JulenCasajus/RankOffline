package org.rankoffline.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MovieSeriesIntegrationTest {
    private lateinit var database: AnimeDatabase
    private lateinit var mediaRepository: GenericMediaRepository
    private lateinit var groupRepository: GenericMediaGroupRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AnimeDatabase::class.java
        ).build()
        mediaRepository = GenericMediaRepository(database) { 100L }
        groupRepository = GenericMediaGroupRepository(database) { 200L }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun movieSearchIsMediaScoped() = runBlocking {
        addItem(movie("1", "Shared title", popularity = 5.0))
        addItem(series("1", 1, "Shared title", popularity = 10.0))

        val results = search(MediaType.MOVIE, query = "Shared")

        assertEquals(listOf("movie:tmdb:1"), results.map { it.id })
        assertTrue(results.all { it.mediaType == MediaType.MOVIE })
    }

    @Test
    fun seriesMergedSearchMatchesMemberAlias() = runBlocking {
        val first = series("10", 1, "Example — Season 1")
        val second = series("10", 2, "Example — Season 2")
        addItem(first)
        addItem(second)
        database.mediaCatalogDao().insertAliases(
            listOf(MediaAliasEntity(first.id, "Hidden legacy title"))
        )
        addGroup(
            MediaGroupEntity(
                id = "series:tmdb:10",
                mediaType = "series",
                groupType = "SEASONS",
                provider = "tmdb",
                providerGroupId = "10",
                displayTitle = "Example"
            ),
            first.id,
            second.id
        )

        val results = search(MediaType.SERIES, query = "legacy", merge = true)

        assertEquals(1, results.size)
        assertTrue(results.single() is MediaListEntry.Group)
        assertEquals("series:tmdb:10", results.single().id)
    }

    @Test
    fun filteringAndPopularitySortingUseMovieMetadata() = runBlocking {
        val popular = movie("1", "Popular", year = 2020, popularity = 90.0)
        val lessPopular = movie("2", "Less", year = 2020, popularity = 5.0)
        val nullPopularity = movie("3", "Unknown", year = 2020, popularity = null)
        val excluded = movie("4", "Other studio", year = 2020, popularity = 100.0)
        for (item in listOf(popular, lessPopular, nullPopularity, excluded)) addItem(item)
        database.mediaCatalogDao().insertGenres(
            listOf(popular, lessPopular, nullPopularity, excluded).map {
                MediaGenreEntity(it.id, "Drama")
            }
        )
        database.mediaCatalogDao().insertCredits(
            listOf(popular, lessPopular, nullPopularity).map {
                MediaCreditEntity(it.id, "PRODUCTION_COMPANY", "Rank Studio")
            } + MediaCreditEntity(excluded.id, "PRODUCTION_COMPANY", "Other")
        )

        val results = mediaRepository.search(
            MediaType.MOVIE,
            query = "",
            sortOrder = CatalogSortOrder.POPULARITY,
            filters = GenericMediaFilters("Drama", 2020, "Rank Studio"),
            mergeEnabled = false
        )

        assertEquals(listOf(popular.id, lessPopular.id, nullPopularity.id), results.map { it.id })
        assertEquals(
            listOf("Other", "Rank Studio"),
            mediaRepository.filterOptions(MediaType.MOVIE).creditNames
        )
    }

    @Test
    fun mergeReplacesMembersAndKeepsUngroupedItems() = runBlocking {
        val first = movie("11", "Collection One")
        val second = movie("12", "Collection Two")
        val ungrouped = movie("13", "Standalone")
        for (item in listOf(first, second, ungrouped)) addItem(item)
        addGroup(
            MediaGroupEntity(
                id = "movie:tmdb:collection:7",
                mediaType = "movie",
                groupType = "COLLECTION",
                provider = "tmdb",
                providerGroupId = "7",
                displayTitle = "Collection"
            ),
            first.id,
            second.id
        )

        val unmerged = search(MediaType.MOVIE, merge = false)
        val merged = search(MediaType.MOVIE, merge = true)

        assertEquals(setOf(first.id, second.id, ungrouped.id), unmerged.map { it.id }.toSet())
        assertEquals(setOf("movie:tmdb:collection:7", ungrouped.id), merged.map { it.id }.toSet())
    }

    @Test
    fun rankingUsesExplicitGroupScoreThenDerivedFallback() = runBlocking {
        val first = movie("21", "First")
        val second = movie("22", "Second")
        for (item in listOf(first, second)) addItem(item)
        val group = MediaGroupEntity(
            id = "movie:tmdb:collection:8",
            mediaType = "movie",
            groupType = "COLLECTION",
            provider = "tmdb",
            providerGroupId = "8",
            displayTitle = "Rated Collection"
        )
        addGroup(group, first.id, second.id)
        mediaRepository.saveSimpleRating(first.id, RatingDraft(), 8.0, 8.0)
        mediaRepository.saveSimpleRating(second.id, RatingDraft(), 6.0, 6.0)
        groupRepository.saveSimpleGroupRating(group.id, RatingDraft(), 9.0, 9.0)

        val explicit = ranking(MediaType.MOVIE, merge = true).single() as MediaListEntry.Group
        assertEquals(9.0, explicit.finalScore!!, 0.0)
        assertEquals(false, explicit.derived)

        groupRepository.deleteGroupRating(group.id)
        val derived = ranking(MediaType.MOVIE, merge = true).single() as MediaListEntry.Group
        assertEquals(7.0, derived.finalScore!!, 0.0)
        assertEquals(true, derived.derived)
    }

    private suspend fun search(
        mediaType: MediaType,
        query: String = "",
        merge: Boolean = false
    ) = mediaRepository.search(
        mediaType,
        query,
        CatalogSortOrder.TITLE_ASC,
        GenericMediaFilters(),
        merge
    )

    private suspend fun ranking(mediaType: MediaType, merge: Boolean) = mediaRepository.ranking(
        mediaType,
        GenericMediaFilters(),
        RankingSortOrder.FINAL_SCORE,
        categoryId = null,
        descending = true,
        mergeEnabled = merge
    )

    private suspend fun addItem(item: MediaItemEntity) {
        mediaRepository.upsertItem(item)
    }

    private suspend fun addGroup(group: MediaGroupEntity, vararg memberIds: String) {
        groupRepository.upsertGroup(
            group,
            memberIds.mapIndexed { index, id -> MediaGroupMemberEntity(group.id, id, index) }
        )
    }

    private fun movie(
        id: String,
        title: String,
        year: Int? = null,
        popularity: Double? = null
    ) = MediaItemEntity(
        id = "movie:tmdb:$id",
        mediaType = "movie",
        provider = "tmdb",
        providerId = id,
        title = title,
        year = year,
        format = "MOVIE",
        popularity = popularity
    )

    private fun series(
        id: String,
        season: Int,
        title: String,
        popularity: Double? = null
    ) = MediaItemEntity(
        id = "series:tmdb:$id:season:$season",
        mediaType = "series",
        provider = "tmdb",
        providerId = "$id:season:$season",
        title = title,
        format = "TV_SEASON",
        partNumber = season,
        popularity = popularity
    )
}
