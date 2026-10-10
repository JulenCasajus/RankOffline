package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreCalculatorTest {
    @Test
    fun defaultWeightsUseExpectedRatio() {
        val scores = mapOf(
            "writing" to 10.0,
            "characters" to 8.0,
            "engagement" to 6.0,
            "visuals" to 4.0,
            "worldbuilding" to 2.0
        )
        val result = ScoreCalculator.calculate(scores, 0.0, DefaultSettings.defaultRatingConfig)
        assertEquals(7.4, result.qualityScore!!, 0.0001)
    }

    @Test
    fun relativeWeightsNeedNotSumToOneHundred() {
        val config = configOf(category("a", 5.0), category("b", 15.0))
        val result = ScoreCalculator.calculate(mapOf("a" to 4.0, "b" to 8.0), 0.0, config)
        assertEquals(7.0, result.qualityScore!!, 0.0001)
    }

    @Test
    fun notApplicableCategoryIsExcluded() {
        val config = configOf(category("a", 50.0), category("b", 50.0))
        val result = ScoreCalculator.calculate(mapOf("a" to 8.0, "b" to null), 0.0, config)
        assertEquals(8.0, result.qualityScore!!, 0.0001)
    }

    @Test
    fun renamingCategoryDoesNotChangeItsStableIdScore() {
        val renamed = category("writing", 100.0).copy(name = "Narrative")
        val result = ScoreCalculator.calculate(mapOf("writing" to 8.5), 0.0, configOf(renamed))
        assertEquals(8.5, result.qualityScore!!, 0.0001)
    }

    @Test
    fun zeroWeightsReturnNoScore() {
        val result = ScoreCalculator.calculate(mapOf("a" to 8.0), 0.0, configOf(category("a", 0.0)))
        assertNull(result.qualityScore)
        assertNull(result.finalScore)
    }

    @Test
    fun allNotApplicableReturnNoScore() {
        val result = ScoreCalculator.calculate(mapOf("a" to null), 0.0, configOf(category("a", 10.0)))
        assertNull(result.qualityScore)
        assertNull(result.finalScore)
    }

    @Test
    fun bonusIsAddedAndFinalScoreIsCapped() {
        val config = configOf(category("a", 10.0), bonus = AdditiveBonus("Taste", "", 2.0, "#000000"))
        assertEquals(9.5, ScoreCalculator.calculate(mapOf("a" to 8.0), 1.5, config).finalScore!!, 0.0001)
        assertEquals(10.0, ScoreCalculator.calculate(mapOf("a" to 9.5), 2.0, config).finalScore!!, 0.0001)
    }

    @Test
    fun nonFiniteInputsCannotProduceNonFiniteScores() {
        val config = configOf(category("a", Double.POSITIVE_INFINITY), category("b", 10.0))
        val result = ScoreCalculator.calculate(mapOf("a" to 5.0, "b" to Double.NaN), Double.NaN, config)
        assertNull(result.qualityScore)
        assertNull(result.finalScore)
    }

    private fun category(id: String, weight: Double) = RatingCategory(id, id, "", weight, "#000000", 0)
    private fun configOf(vararg categories: RatingCategory, bonus: AdditiveBonus? = null) =
        RatingSystemConfig(categories.toList(), bonus)
}
