package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenericGroupRatingLogicTest {
    @Test
    fun derivedAverageUsesOnlyRatedMembers() {
        val derived = deriveMediaGroupRating(
            ratings = listOf(rating("a", 8.0), rating("b", 6.0), rating("c", null)),
            scores = emptyList()
        )

        assertEquals(7.0, derived.finalScore!!, 0.0)
        assertEquals(2, derived.ratedCount)
    }

    @Test
    fun zeroIsIncludedInDerivedAverage() {
        val derived = deriveMediaGroupRating(
            ratings = listOf(rating("a", 0.0), rating("b", 10.0)),
            scores = emptyList()
        )

        assertEquals(5.0, derived.finalScore!!, 0.0)
        assertEquals(2, derived.ratedCount)
    }

    @Test
    fun categoryAverageIgnoresNotApplicableRows() {
        val derived = deriveMediaGroupRating(
            ratings = listOf(rating("a", 8.0), rating("b", 7.0), rating("c", 6.0)),
            scores = listOf(
                MediaRatingScoreEntity("a", "writing", 8.0, true),
                MediaRatingScoreEntity("b", "writing", null, false),
                MediaRatingScoreEntity("c", "writing", 6.0, true)
            )
        )

        assertEquals(7.0, derived.categoryScores.getValue("writing"), 0.0)
    }

    @Test
    fun qualityAndTasteAverageOnlyAvailableFiniteValuesFromRatedMembers() {
        val derived = deriveMediaGroupRating(
            ratings = listOf(
                rating("a", 8.0, quality = 7.0, additive = 0.2),
                rating("b", 6.0, quality = null, additive = Double.NaN),
                rating("unrated", null, quality = 10.0, additive = 1.0)
            ),
            scores = emptyList()
        )

        assertEquals(7.0, derived.qualityScore!!, 0.0)
        assertEquals(0.2, derived.additiveScore!!, 0.0)
        assertEquals(2, derived.ratedCount)
        assertNull(deriveMediaGroupRating(emptyList(), emptyList()).finalScore)
    }

    private fun rating(
        id: String,
        final: Double?,
        quality: Double? = final,
        additive: Double = 0.0
    ) = MediaRatingEntity(id, additive, quality, final, "", "Completed", 1L)
}
