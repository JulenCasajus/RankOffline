package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class MediaScreenStateTest {
    @Test
    fun searchQueriesAreIndependent() {
        val states = defaultSearchUiStates()
            .updateSearchUiState(MediaType.ANIME) { it.copy(query = "Monster") }
            .updateSearchUiState(MediaType.BOOK) { it.copy(query = "Dune") }

        assertEquals("Monster", states.searchStateFor(MediaType.ANIME).query)
        assertEquals("Dune", states.searchStateFor(MediaType.BOOK).query)
        assertEquals("", states.searchStateFor(MediaType.MANGA).query)
    }

    @Test
    fun clearingQueryUsesTheActiveMediaUpdateWithoutChangingOtherQueries() {
        val states = defaultSearchUiStates()
            .updateSearchUiState(MediaType.ANIME) { it.copy(query = "Frieren") }
            .updateSearchUiState(MediaType.MANGA) { it.copy(query = "Berserk") }
            .updateSearchUiState(MediaType.MANGA) { it.copy(query = "") }

        assertEquals("Frieren", states.searchStateFor(MediaType.ANIME).query)
        assertEquals("", states.searchStateFor(MediaType.MANGA).query)
    }

    @Test
    fun searchSortAndFiltersAreIndependent() {
        val animeFilters = AnimeFilters(genre = "Drama", year = 2004)
        val movieFilters = AnimeFilters(studio = "Studio", year = 1999)
        val states = defaultSearchUiStates()
            .updateSearchUiState(MediaType.ANIME) {
                it.copy(sortOrder = CatalogSortOrder.TITLE_ASC, filters = animeFilters)
            }
            .updateSearchUiState(MediaType.MOVIE) {
                it.copy(sortOrder = CatalogSortOrder.POPULARITY_REVERSE, filters = movieFilters)
            }

        assertEquals(CatalogSortOrder.TITLE_ASC, states.searchStateFor(MediaType.ANIME).sortOrder)
        assertEquals(animeFilters, states.searchStateFor(MediaType.ANIME).filters)
        assertEquals(CatalogSortOrder.POPULARITY_REVERSE, states.searchStateFor(MediaType.MOVIE).sortOrder)
        assertEquals(movieFilters, states.searchStateFor(MediaType.MOVIE).filters)
    }

    @Test
    fun rankingStateIsIndependent() {
        val states = defaultRankingUiStates()
            .updateRankingUiState(MediaType.ANIME) {
                it.copy(sortOrder = RankingSortOrder.CATEGORY_SCORE, descending = true, categoryId = "visuals")
            }
            .updateRankingUiState(MediaType.MANGA) {
                it.copy(sortOrder = RankingSortOrder.QUALITY_SCORE, descending = false, categoryId = null)
            }

        assertEquals("visuals", states.rankingStateFor(MediaType.ANIME).categoryId)
        assertEquals(RankingSortOrder.CATEGORY_SCORE, states.rankingStateFor(MediaType.ANIME).sortOrder)
        assertEquals(RankingSortOrder.QUALITY_SCORE, states.rankingStateFor(MediaType.MANGA).sortOrder)
        assertEquals(false, states.rankingStateFor(MediaType.MANGA).descending)
    }

    @Test
    fun newMediaStartsWithIndependentDefaults() {
        val states = defaultSearchUiStates()
            .updateSearchUiState(MediaType.ANIME) {
                it.copy(query = "Naruto", filters = AnimeFilters(genre = "Action"))
            }

        assertEquals(
            SearchUiState(sortOrder = CatalogSortOrder.POPULARITY),
            states.searchStateFor(MediaType.BOOK)
        )
        assertNotSame(states.searchStateFor(MediaType.ANIME), states.searchStateFor(MediaType.BOOK))
    }

    @Test
    fun persistedSortOrdersAreAppliedWithoutChangingOtherSearchState() {
        val original = defaultSearchUiStates()
            .updateSearchUiState(MediaType.ANIME) { it.copy(query = "Frieren") }
            .updateSearchUiState(MediaType.BOOK) {
                it.copy(genericFilters = GenericMediaFilters(year = 1965))
            }

        val restored = original.withCatalogSortOrders(
            mapOf(
                MediaType.ANIME to CatalogSortOrder.TITLE_ASC,
                MediaType.BOOK to CatalogSortOrder.TITLE_DESC
            )
        )

        assertEquals(CatalogSortOrder.TITLE_ASC, restored.getValue(MediaType.ANIME).sortOrder)
        assertEquals("Frieren", restored.getValue(MediaType.ANIME).query)
        assertEquals(CatalogSortOrder.TITLE_DESC, restored.getValue(MediaType.BOOK).sortOrder)
        assertEquals(1965, restored.getValue(MediaType.BOOK).genericFilters.year)
        assertEquals(CatalogSortOrder.POPULARITY, restored.getValue(MediaType.MANGA).sortOrder)
    }

    @Test
    fun clearingFiltersChangesOnlySelectedMedia() {
        val filters = AnimeFilters(genre = "Fantasy")
        val states = defaultSearchUiStates()
            .updateSearchUiState(MediaType.ANIME) { it.copy(filters = filters) }
            .updateSearchUiState(MediaType.BOOK) { it.copy(filters = filters) }
            .updateSearchUiState(MediaType.BOOK) { it.copy(filters = AnimeFilters()) }

        assertEquals(filters, states.searchStateFor(MediaType.ANIME).filters)
        assertEquals(AnimeFilters(), states.searchStateFor(MediaType.BOOK).filters)
    }

    @Test
    fun invalidRankingCategoryFallsBackSafely() {
        val state = RankingUiState(
            sortOrder = RankingSortOrder.CATEGORY_SCORE,
            categoryId = "visuals"
        )

        assertEquals(state, state.withValidCategory(setOf("visuals", "writing")))
        assertEquals(
            RankingUiState(sortOrder = RankingSortOrder.FINAL_SCORE),
            state.withValidCategory(setOf("prose", "writing"))
        )
    }

    @Test
    fun updateHelperChangesExactlyOneMediaType() {
        val initial = defaultRankingUiStates()
        val updated = initial.updateRankingUiState(MediaType.SERIES) {
            it.copy(descending = false, filters = AnimeFilters(year = 2020))
        }

        MediaType.entries.filterNot { it == MediaType.SERIES }.forEach { mediaType ->
            assertEquals(initial.getValue(mediaType), updated.getValue(mediaType))
        }
        assertEquals(false, updated.getValue(MediaType.SERIES).descending)
        assertEquals(2020, updated.getValue(MediaType.SERIES).filters.year)
    }

    @Test
    fun ratingEditorIdentityIncludesPackAndGroupKind() {
        val anime = Anime(id = "shared-id", title = "Anime", mediaType = MediaType.ANIME)
        val manga = Anime(id = "shared-id", title = "Manga", mediaType = MediaType.MANGA)
        val state = RatingEditorState(anime, isGroup = false)

        assertEquals(true, state.matches(anime, isGroup = false))
        assertEquals(false, state.matches(anime, isGroup = true))
        assertEquals(false, state.matches(manga, isGroup = false))
    }
}
