package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleRatingTest {
    @Test
    fun exactlyTwentyOneHalfPointValuesArePersistible() {
        val values = (0..20).map { it / 2.0 }

        assertEquals(21, values.size)
        values.forEach { value ->
            assertTrue("Expected $value to be valid", isDiscreteRatingValue(value))
            assertEquals(value, snapRatingValue(value)!!, 0.0)
        }
    }

    @Test
    fun rejectsOutOfRangeNonFiniteAndNonStepValues() {
        listOf(-0.01, 10.01, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertNull(snapRatingValue(it))
            assertFalse(isDiscreteRatingValue(it))
        }
        listOf(0.25, 8.37, 8.4999997, 9.99).forEach {
            assertFalse("Expected $it not to be persistible", isDiscreteRatingValue(it))
        }
    }

    @Test
    fun sliderSnappingProducesExactHalfPointsWithoutFloatArtifacts() {
        assertEquals(0.0, snapRatingValue(0.01)!!, 0.0)
        assertEquals(0.5, snapRatingValue(0.49)!!, 0.0)
        assertEquals(8.5, snapRatingValue(8.4999997)!!, 0.0)
        assertEquals(9.5, snapRatingValue(9.49)!!, 0.0)
        assertEquals(10.0, snapRatingValue(9.99)!!, 0.0)
        assertEquals(19, RATING_SLIDER_STEPS)
    }

    @Test
    fun directEntryAcceptsUpToTwoDecimalsAndBothDecimalSeparators() {
        mapOf(
            "9.24" to 9.24,
            "7.1" to 7.1,
            "4" to 4.0,
            "8.63" to 8.63,
            "7,25" to 7.25,
            "0.00" to 0.0,
            "10.00" to 10.0
        ).forEach { (input, expected) ->
            assertEquals(expected, parseDirectRatingValue(input)!!, 0.0)
        }
    }

    @Test
    fun directEntryRejectsInvalidPrecisionRangeAndNonFiniteValues() {
        listOf("", "-0.01", "10.01", "8.999", "NaN", "Infinity", "text").forEach {
            assertNull("Expected $it to be invalid", parseDirectRatingValue(it))
        }
        listOf(-0.01, 10.01, 8.999, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertNull(normalizeDirectRatingValue(it))
        }
    }

    @Test
    fun simpleQualityAndPersonalTasteProduceTheFinalScore() {
        assertEquals(9.2, finalScoreFromQuality(8.5, 0.7)!!, 0.0)
        assertEquals(10.0, finalScoreFromQuality(9.5, 1.0)!!, 0.0)
        assertEquals(0.0, finalScoreFromQuality(0.0, 0.0)!!, 0.0)
    }
}
