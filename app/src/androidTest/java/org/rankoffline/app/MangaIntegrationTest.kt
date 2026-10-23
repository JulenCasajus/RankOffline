package org.rankoffline.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaIntegrationTest {
    private lateinit var database: AnimeDatabase
    private lateinit var mediaRepository: GenericMediaRepository
    private lateinit var groupRepository: GenericMediaGroupRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AnimeDatabase::class.java
        ).build()
        mediaRepository = GenericMediaRepository(database, now = { 100L })
        groupRepository = GenericMediaGroupRepository(database) { 200L }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun mangaSearchUsesCanonicalAndAliasTitles() = runBlocking {
        val frieren = manga("test-frieren", "Sousou no Frieren")
        addItem(frieren)
        database.mediaCatalogDao().insertAliases(
            listOf(
                MediaAliasEntity(frieren.id, "Frieren: Beyond Journey's End", "en"),
                MediaAliasEntity(frieren.id, "葬送のフリーレン", "ja")
            )
        )

        for (query in listOf(
            "Sousou no Frieren",
            "Frieren: Beyond Journey's End",
            "frieren: beyond journey's end",
            "葬送のフリーレン"
        )) {
            assertEquals(listOf(frieren.id), search(query = query).map { it.id })
        }
    }

    @Test
    fun mangaSearchAndFiltersAreMediaScoped() = runBlocking {
        val matching = manga("match", "Shared", year = 2020, demographic = "seinen")
        val wrongAuthor = manga("other-author", "Shared", year = 2020, demographic = "shounen")
        val movie = movie("1", "Shared", year = 2020)
        val series = series("1", "Shared", year = 2020)
        for (item in listOf(matching, wrongAuthor, movie, series)) addItem(item)
        database.mediaCatalogDao().insertGenres(
            listOf(
                MediaGenreEntity(matching.id, "Drama"),
                MediaGenreEntity(wrongAuthor.id, "Drama"),
                MediaGenreEntity(movie.id, "Drama"),
                MediaGenreEntity(series.id, "Drama")
            )
        )
        database.mediaCatalogDao().insertCredits(
            listOf(
                MediaCreditEntity(matching.id, "AUTHOR", "Target Author"),
                MediaCreditEntity(wrongAuthor.id, "AUTHOR", "Other Author")
            )
        )

        val results = mediaRepository.search(
            MediaType.MANGA,
            "Shared",
            CatalogSortOrder.TITLE_ASC,
            GenericMediaFilters(genre = "Drama", year = 2020, creditName = "Target Author"),
            mergeEnabled = false
        )
        val options = mediaRepository.filterOptions(MediaType.MANGA)

        assertEquals(listOf(matching.id), results.map { it.id })
        assertTrue(results.all { it.mediaType == MediaType.MANGA })
        assertEquals(listOf("Drama"), options.genres)
        assertEquals(listOf(2020), options.years)
        assertEquals(listOf("Other Author", "Target Author"), options.creditNames)
        assertTrue(options.demographics.isEmpty())
    }

    @Test
    fun mangaIndividualRatingUsesMangaCategories() = runBlocking {
        val item = manga("rated", "Rated Manga")
        val movie = movie("rated", "Rated Movie")
        addItem(item)
        addItem(movie)
        mediaRepository.saveSimpleRating(movie.id, RatingDraft(), 5.0, 5.0)
        val config = DefaultSettings.ratingConfig(MediaType.MANGA)
        val profile = DefaultSettings.profiles(MediaType.MANGA, config.categories).profiles.first()
        val draft = RatingDraft(
            categoryScores = mapOf("art" to 8.0, "paneling" to 6.0),
            additiveScore = 0.5,
            profileId = profile.id
        )

        mediaRepository.saveElaborateRating(
            item.id,
            draft,
            config,
            profileId = profile.id
        )

        val rating = mediaRepository.rating(item.id)
        assertNotNull(rating)
        val expected = ScoreCalculator.calculate(draft.categoryScores, draft.additiveScore, config)
        assertEquals(expected.qualityScore!!, rating!!.qualityScore!!, 0.0001)
        assertEquals(expected.finalScore!!, rating.finalScore!!, 0.0001)
        assertEquals(profile.id, rating.profileId)
        assertEquals(setOf("art", "paneling"), mediaRepository.ratingScores(item.id).map { it.categoryId }.toSet())
        assertEquals(5.0, mediaRepository.rating(movie.id)?.finalScore!!, 0.0)
    }

    @Test
    fun mergeVariantsReplacesMembersAndMatchesMemberAlias() = runBlocking {
        val first = manga(
            "variant-a",
            "Variant A",
            localCoverPath = "/private/covers/manga/a.cover",
            coverState = CoverResolutionState.AVAILABLE
        )
        val second = manga("variant-b", "Variant B")
        val ungrouped = manga("standalone", "Standalone")
        for (item in listOf(first, second, ungrouped)) addItem(item)
        database.mediaCatalogDao().insertAliases(
            listOf(MediaAliasEntity(second.id, "Hidden English Variant", "en"))
        )
        addVariantsGroup("variants-1", first.id, second.id)

        val unmerged = search(merge = false)
        val merged = search(merge = true)
        val aliasMatch = search(query = "Hidden English Variant", merge = true)

        assertEquals(setOf(first.id, second.id, ungrouped.id), unmerged.map { it.id }.toSet())
        assertEquals(setOf("manga:mangadex:variants:variants-1", ungrouped.id), merged.map { it.id }.toSet())
        assertEquals(1, aliasMatch.size)
        val group = aliasMatch.single() as MediaListEntry.Group
        assertEquals("manga:mangadex:variants:variants-1", group.id)
        assertEquals(first.localCoverPath, group.value.localCoverPath)
        assertEquals(CoverResolutionState.AVAILABLE, group.value.coverResolutionState)
    }

    @Test
    fun mergedVariantRankingUsesExplicitThenDerivedFallback() = runBlocking {
        val first = manga("rank-a", "Rank A")
        val second = manga("rank-b", "Rank B")
        addItem(first)
        addItem(second)
        val groupId = addVariantsGroup("ranked", first.id, second.id)
        mediaRepository.saveSimpleRating(first.id, RatingDraft(), 8.0, 8.0)
        mediaRepository.saveSimpleRating(second.id, RatingDraft(), 6.0, 6.0)
        groupRepository.saveSimpleGroupRating(groupId, RatingDraft(), 9.0, 9.0)

        val explicit = ranking().single() as MediaListEntry.Group
        assertEquals(9.0, explicit.finalScore!!, 0.0)
        assertEquals(false, explicit.derived)

        groupRepository.deleteGroupRating(groupId)
        val derived = ranking().single() as MediaListEntry.Group
        assertEquals(7.0, derived.finalScore!!, 0.0)
        assertEquals(true, derived.derived)
    }

    private suspend fun search(
        query: String = "",
        merge: Boolean = false
    ) = mediaRepository.search(
        MediaType.MANGA,
        query,
        CatalogSortOrder.TITLE_ASC,
        GenericMediaFilters(),
        merge
    )

    private suspend fun ranking() = mediaRepository.ranking(
        MediaType.MANGA,
        GenericMediaFilters(),
        RankingSortOrder.FINAL_SCORE,
        categoryId = null,
        descending = true,
        mergeEnabled = true
    )

    private suspend fun addItem(item: MediaItemEntity) {
        mediaRepository.upsertItem(item)
    }

    private suspend fun addVariantsGroup(suffix: String, vararg memberIds: String): String {
        val id = "manga:mangadex:variants:$suffix"
        groupRepository.upsertGroup(
            MediaGroupEntity(
                id = id,
                mediaType = "manga",
                groupType = "VARIANTS",
                provider = "mangadex",
                providerGroupId = suffix,
                displayTitle = "Variants",
                representativeMediaId = memberIds.first()
            ),
            memberIds.mapIndexed { index, mediaId -> MediaGroupMemberEntity(id, mediaId, index) }
        )
        return id
    }

    private fun manga(
        id: String,
        title: String,
        year: Int? = null,
        demographic: String? = null,
        localCoverPath: String? = null,
        coverState: CoverResolutionState = CoverResolutionState.UNKNOWN
    ) = MediaItemEntity(
        id = "manga:mangadex:$id",
        mediaType = "manga",
        provider = "mangadex",
        providerId = id,
        title = title,
        year = year,
        format = "MANGA",
        demographic = demographic,
        localCoverPath = localCoverPath,
        coverResolutionState = coverState.name
    )

    private fun movie(id: String, title: String, year: Int? = null) = MediaItemEntity(
        id = "movie:tmdb:$id",
        mediaType = "movie",
        provider = "tmdb",
        providerId = id,
        title = title,
        year = year,
        format = "MOVIE"
    )

    private fun series(id: String, title: String, year: Int? = null) = MediaItemEntity(
        id = "series:tmdb:$id:season:1",
        mediaType = "series",
        provider = "tmdb",
        providerId = "$id:season:1",
        title = title,
        year = year,
        format = "TV_SEASON",
        partNumber = 1
    )
}
