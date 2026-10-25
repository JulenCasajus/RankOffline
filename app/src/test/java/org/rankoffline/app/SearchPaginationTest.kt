package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPaginationTest {
    @Test
    fun initialAndSubsequentPagesAppendWithoutDuplicates() {
        val initial = (0 until 200).map(Int::toString)
        val next = (199 until 249).map(Int::toString)
        val third = (249 until 299).map(Int::toString)

        val first = SearchPageAccumulator<String>().append(initial, 200) { it }
        val second = first.append(next, 50) { it }
        val result = second.append(third, 50) { it }

        assertEquals(299, result.items.size)
        assertEquals(300, result.nextOffset)
        assertTrue(result.hasMore)
    }

    @Test
    fun shortEmptyAndExactPagesHaveReliableExhaustion() {
        val empty = SearchPageAccumulator<String>().append(emptyList(), 200) { it }
        val short = SearchPageAccumulator<String>().append(listOf("a"), 200) { it }
        val exact = SearchPageAccumulator<String>().append((0 until 200).map(Int::toString), 200) { it }

        assertFalse(empty.hasMore)
        assertFalse(short.hasMore)
        assertTrue(exact.hasMore)
        assertEquals(1, short.nextOffset)
    }

    @Test
    fun resetStartsAtZeroWithNoStaleRows() {
        val loaded = SearchPageAccumulator<String>().append((0 until 200).map(Int::toString), 200) { it }
        val reset = SearchPageAccumulator<String>()

        assertEquals(200, loaded.nextOffset)
        assertEquals(0, reset.nextOffset)
        assertTrue(reset.items.isEmpty())
    }

    @Test
    fun switchingMediaNeverExposesThePreviousSearchOrRankingSnapshot() {
        val anime = Anime("mal:1", "Anime", mediaType = MediaType.ANIME)
        val manga = Anime("manga:1", "Manga", mediaType = MediaType.MANGA)
        val animeSearch = SearchPageState(MediaType.ANIME, listOf(anime), loadingInitial = false)
        val mangaLoading = SearchPageState(MediaType.MANGA, loadingInitial = true)
        val animeRanking = RankingResultState(MediaType.ANIME, listOf(anime), loading = false)

        assertEquals(listOf(anime), animeSearch.visibleItemsFor(MediaType.ANIME))
        assertTrue(animeSearch.visibleItemsFor(MediaType.MANGA).isEmpty())
        assertTrue(mangaLoading.visibleItemsFor(MediaType.MANGA).isEmpty())
        assertTrue(animeRanking.visibleItemsFor(MediaType.BOOK).isEmpty())
        assertEquals(
            listOf(manga),
            RankingResultState(MediaType.MANGA, listOf(manga), loading = false)
                .visibleItemsFor(MediaType.MANGA)
        )
    }

    @Test
    fun bottomNavigationOnlySignalsTopOnReselection() {
        assertEquals(BottomNavigationAction.RESELECT, bottomNavigationAction(0, 0))
        assertEquals(BottomNavigationAction.RESELECT, bottomNavigationAction(1, 1))
        assertEquals(BottomNavigationAction.SWITCH, bottomNavigationAction(0, 1))
    }

    @Test
    fun revisionsAreScopedToTheChangedMediaAndDomain() {
        val revisions = MediaRevisionTracker()

        revisions.catalogChanged(MediaType.MANGA)
        revisions.ratingsChanged(MediaType.MOVIE)

        assertEquals(1L, revisions.catalog.value.getValue(MediaType.MANGA))
        assertEquals(0L, revisions.catalog.value.getValue(MediaType.ANIME))
        assertEquals(1L, revisions.ratings.value.getValue(MediaType.MOVIE))
        assertEquals(0L, revisions.ratings.value.getValue(MediaType.MANGA))
    }

    @Test
    fun ratingRevisionChangesRankingRequestIdentityWithoutChangingFilters() {
        val original = RankingRequest(
            mediaType = MediaType.MOVIE,
            animeFilters = AnimeFilters(),
            genericFilters = GenericMediaFilters(),
            sort = RankingSortOrder.FINAL_SCORE,
            descending = true,
            categoryId = null,
            merge = true,
            catalogRevision = 4L,
            ratingRevision = 8L
        )

        assertFalse(original == original.copy(ratingRevision = 9L))
        assertFalse(original == original.copy(catalogRevision = 5L))
    }

    @Test
    fun neutralCatalogModelPreservesGenericIdentityAndMetadata() {
        val entry = MediaListEntry.Item(
            MediaItem(
                id = "series:tmdb:42:season:3",
                mediaType = MediaType.SERIES,
                title = "Season 3",
                year = 2024,
                genre = "Drama",
                filterCredit = "Network"
            )
        )

        val model = entry.toCatalogItemUiModel()
        val legacyBoundary = model.toLegacyAnimeDisplay()

        assertEquals("series:tmdb:42:season:3", model.stableId)
        assertEquals(MediaType.SERIES, model.mediaType)
        assertEquals("Drama", model.genre)
        assertEquals("Network", legacyBoundary.filterCredit)
    }
}
