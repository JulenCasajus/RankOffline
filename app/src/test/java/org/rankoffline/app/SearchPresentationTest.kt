package org.rankoffline.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPresentationTest {
    @Test
    fun aliasChoicePrefersCanonicalThenExactPrefixAndSubstringDeterministically() {
        val aliases = listOf("The Blue Archive Story", "Blue Archive Extended", "Blue Archive")

        assertNull(bestMatchingAliasTitle("Canonical Blue Archive", aliases, "blue archive"))
        assertEquals("Blue Archive", bestMatchingAliasTitle("Canonical", aliases, "BLUE ARCHIVE"))
        assertEquals(
            "Blue Archive Extended",
            bestMatchingAliasTitle("Canonical", aliases, "archive ex")
        )
        assertEquals(
            "The Blue Archive Story",
            bestMatchingAliasTitle("Canonical", aliases, "archive story")
        )
    }

    @Test
    fun aliasesRequireThreeUnicodeCodePoints() {
        assertNull(bestMatchingAliasTitle("Canonical", listOf("東京物語"), "東"))
        assertNull(bestMatchingAliasTitle("Canonical", listOf("東京物語"), "東京"))
        assertEquals("東京物語", bestMatchingAliasTitle("Canonical", listOf("東京物語"), "東京物"))
    }

    @Test
    fun samePackRefreshKeepsRowsButQueryChangeRestoresCanonicalTitle() {
        val aliasRow = Anime(
            id = "mal:1",
            title = "Alias title",
            canonicalTitle = "Canonical title"
        )
        val previous = SearchPageState(
            mediaType = MediaType.ANIME,
            query = "alias",
            items = listOf(aliasRow),
            loadingInitial = false,
            hasMore = true
        )

        val sameQuery = retainedSearchPageState(previous, MediaType.ANIME, "alias")
        assertFalse(sameQuery.loadingInitial)
        assertEquals("Alias title", sameQuery.items.single().title)

        val changedQuery = retainedSearchPageState(previous, MediaType.ANIME, "other")
        assertFalse(changedQuery.loadingInitial)
        assertEquals("Canonical title", changedQuery.items.single().title)
        assertNull(changedQuery.items.single().canonicalTitle)

        val otherPack = retainedSearchPageState(previous, MediaType.MANGA, "alias")
        assertTrue(otherPack.loadingInitial)
        assertTrue(otherPack.items.isEmpty())
    }

    @Test
    fun rankingRefreshRetainsOnlyRowsOwnedByTheSamePack() {
        val previous = RankingResultState(
            mediaType = MediaType.MOVIE,
            items = listOf(Anime("movie:1", "Movie", MediaType.MOVIE)),
            loading = false
        )

        assertFalse(retainedRankingResultState(previous, MediaType.MOVIE).loading)
        val switched = retainedRankingResultState(previous, MediaType.BOOK)
        assertTrue(switched.loading)
        assertTrue(switched.items.isEmpty())
    }

    @Test
    fun welcomeMetricsAreTenPercentShorterWithoutShrinkingTouchTargets() {
        val tall = welcomeLayoutMetrics(820.dp)
        val compact = welcomeLayoutMetrics(600.dp)

        assertEquals(212.4f, tall.optionHeight.value, 0.01f)
        assertEquals(135f, compact.optionHeight.value, 0.01f)
        assertTrue(tall.selectorMinHeight >= 48.dp)
        assertTrue(compact.selectorMinHeight >= 48.dp)
    }
}
