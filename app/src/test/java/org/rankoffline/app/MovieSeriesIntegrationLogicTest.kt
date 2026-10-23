package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovieSeriesIntegrationLogicTest {
    @Test
    fun mediaTypesDispatchToExpectedPersistencePaths() {
        assertEquals(MediaDataPath.GENERIC, MediaType.ANIME.dataPath())
        assertEquals(MediaDataPath.GENERIC, MediaType.MANGA.dataPath())
        assertEquals(MediaDataPath.GENERIC, MediaType.MOVIE.dataPath())
        assertEquals(MediaDataPath.GENERIC, MediaType.SERIES.dataPath())
        assertEquals(MediaDataPath.GENERIC, MediaType.BOOK.dataPath())
    }

    @Test
    fun genericMediaUseTheirProviderCreditRoles() {
        assertEquals("PRODUCTION_COMPANY", MediaType.MOVIE.genericFilterCreditRole())
        assertEquals("NETWORK", MediaType.SERIES.genericFilterCreditRole())
        assertEquals("STUDIO", MediaType.ANIME.genericFilterCreditRole())
        assertEquals("AUTHOR", MediaType.MANGA.genericFilterCreditRole())
        assertEquals("AUTHOR", MediaType.BOOK.genericFilterCreditRole())
    }

    @Test
    fun mergeLabelsDistinguishCollectionsFromSeasons() {
        assertEquals(R.string.merge_collections, MediaType.MOVIE.mergeLabelResource)
        assertEquals(R.string.merge_seasons, MediaType.SERIES.mergeLabelResource)
        assertEquals(R.string.merge_seasons, MediaType.ANIME.mergeLabelResource)
        assertEquals(R.string.merge_variants, MediaType.MANGA.mergeLabelResource)
        assertNull(MediaType.BOOK.mergeLabelResource)

        assertEquals("VARIANTS", MediaType.MANGA.genericGroupType())
        assertEquals("COLLECTION", MediaType.MOVIE.genericGroupType())
        assertEquals("SEASONS", MediaType.SERIES.genericGroupType())
        assertEquals("RELATIONS", MediaType.ANIME.genericGroupType())
    }

    @Test
    fun booksDoNotSupportMergeOrResolveAMergeLabel() {
        assertEquals(false, MediaType.BOOK.supportsMerge())
        assertNull(MediaType.BOOK.mergeLabelResource)
        assertNull(MediaType.BOOK.genericGroupType())
        assertEquals(true, MediaType.ANIME.supportsMerge())
        assertEquals(true, MediaType.MANGA.supportsMerge())
        assertEquals(true, MediaType.MOVIE.supportsMerge())
        assertEquals(true, MediaType.SERIES.supportsMerge())
    }

    @Test
    fun genericFiltersRemainIsolatedByMediaType() {
        val states = defaultSearchUiStates()
            .updateSearchUiState(MediaType.MOVIE) {
                it.copy(genericFilters = GenericMediaFilters(genre = "Drama", year = 1999, creditName = "Studio"))
            }
            .updateSearchUiState(MediaType.SERIES) {
                it.copy(genericFilters = GenericMediaFilters(genre = "Mystery", year = 2020, creditName = "Network"))
            }

        assertEquals("Studio", states.searchStateFor(MediaType.MOVIE).genericFilters.creditName)
        assertEquals("Network", states.searchStateFor(MediaType.SERIES).genericFilters.creditName)
        assertEquals(GenericMediaFilters(), states.searchStateFor(MediaType.ANIME).genericFilters)
    }

    @Test
    fun everyMediaStartsWithMostPopularFirst() {
        val states = defaultSearchUiStates()

        MediaType.entries.forEach { mediaType ->
            assertEquals(CatalogSortOrder.POPULARITY, states.searchStateFor(mediaType).sortOrder)
        }
        assertEquals(
            listOf(CatalogSortOrder.POPULARITY, CatalogSortOrder.TITLE_ASC),
            MediaType.BOOK.catalogSortCriteria()
        )
    }
}
