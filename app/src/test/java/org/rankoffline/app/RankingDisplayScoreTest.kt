package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingDisplayScoreTest {
    @Test
    fun displayedScoreFollowsSelectedRankingMetric() {
        val anime = Anime(
            id = "test:1",
            title = "Example",
            finalScore = 8.43,
            baseScore = 7.25,
            additiveScore = 0.6,
            categoryScore = 9.5
        )

        assertEquals(8.43, anime.rankingDisplayScore(RankingSortOrder.FINAL_SCORE)!!, 0.001)
        assertEquals(7.25, anime.rankingDisplayScore(RankingSortOrder.QUALITY_SCORE)!!, 0.001)
        assertEquals(6.0, anime.rankingDisplayScore(RankingSortOrder.PERSONAL_TASTE)!!, 0.001)
        assertEquals(9.5, anime.rankingDisplayScore(RankingSortOrder.CATEGORY_SCORE)!!, 0.001)
    }

    @Test
    fun missingCategoryDoesNotFallBackToFinalScoreAndZeroRemainsValid() {
        val anime = Anime(id = "test:2", title = "Example", finalScore = 8.0, additiveScore = 0.0)

        assertNull(anime.rankingDisplayScore(RankingSortOrder.CATEGORY_SCORE))
        assertNull(anime.rankingDisplayScore(RankingSortOrder.QUALITY_SCORE))
        assertEquals(0.0, anime.rankingDisplayScore(RankingSortOrder.PERSONAL_TASTE)!!, 0.0)
    }

    @Test
    fun catalogDirectionToggleInvertsBothCriteriaWithoutChangingCriterion() {
        assertFalse(CatalogSortOrder.POPULARITY.isReversed)
        assertEquals(CatalogSortOrder.POPULARITY_REVERSE, CatalogSortOrder.POPULARITY.inverted())
        assertEquals(CatalogSortOrder.POPULARITY, CatalogSortOrder.POPULARITY_REVERSE.inverted())

        assertFalse(CatalogSortOrder.TITLE_ASC.isReversed)
        assertTrue(CatalogSortOrder.TITLE_DESC.isReversed)
        assertEquals(CatalogSortOrder.TITLE_DESC, CatalogSortOrder.TITLE_ASC.inverted())
        assertEquals(CatalogSortOrder.TITLE_ASC, CatalogSortOrder.TITLE_DESC.inverted())
        assertEquals(CatalogSortOrder.TITLE_DESC, CatalogSortOrder.TITLE_ASC.withReversed(true))
    }
}
