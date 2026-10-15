package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovieSeriesIntegrationLogicTest {
    @Test
    fun mediaTypesDispatchToExpectedPersistencePaths() {
        assertEquals(MediaDataPath.ANIME, MediaType.ANIME.dataPath())
        assertEquals(MediaDataPath.GENERIC, MediaType.MANGA.dataPath())
        assertEquals(MediaDataPath.GENERIC, MediaType.MOVIE.dataPath())
        assertEquals(MediaDataPath.GENERIC, MediaType.SERIES.dataPath())
        assertEquals(MediaDataPath.UNSUPPORTED, MediaType.BOOK.dataPath())
    }

    @Test
    fun movieAndSeriesUseTheirProviderCreditRoles() {
        assertEquals("PRODUCTION_COMPANY", MediaType.MOVIE.genericFilterCreditRole())
        assertEquals("NETWORK", MediaType.SERIES.genericFilterCreditRole())
        assertNull(MediaType.ANIME.genericFilterCreditRole())
        assertNull(MediaType.MANGA.genericFilterCreditRole())
        assertNull(MediaType.BOOK.genericFilterCreditRole())
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
    }

    @Test
    fun genericFiltersRemainIsolatedByMediaType() {
        val states = defaultSearchUiStates()
            .updateSearchUiState(MediaType.MOVIE) {
                it.copy(genericFilters = GenericMediaFilters("Drama", 1999, "Studio"))
            }
            .updateSearchUiState(MediaType.SERIES) {
                it.copy(genericFilters = GenericMediaFilters("Mystery", 2020, "Network"))
            }

        assertEquals("Studio", states.searchStateFor(MediaType.MOVIE).genericFilters.creditName)
        assertEquals("Network", states.searchStateFor(MediaType.SERIES).genericFilters.creditName)
        assertEquals(GenericMediaFilters(), states.searchStateFor(MediaType.ANIME).genericFilters)
    }

    @Test
    fun mangaStartsTitleSortedWithoutChangingOtherMediaDefaults() {
        val states = defaultSearchUiStates()

        assertEquals(CatalogSortOrder.TITLE_ASC, states.searchStateFor(MediaType.MANGA).sortOrder)
        assertEquals(CatalogSortOrder.POPULARITY, states.searchStateFor(MediaType.ANIME).sortOrder)
        assertEquals(CatalogSortOrder.POPULARITY, states.searchStateFor(MediaType.MOVIE).sortOrder)
        assertEquals(CatalogSortOrder.POPULARITY, states.searchStateFor(MediaType.SERIES).sortOrder)
    }
}
