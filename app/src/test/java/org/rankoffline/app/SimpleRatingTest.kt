package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimpleRatingTest {
    @Test
    fun acceptsZeroToTenWithAtMostTwoDecimals() {
        mapOf(
            "0" to 0.0,
            "0.00" to 0.0,
            "8.37" to 8.37,
            "9.99" to 9.99,
            "10" to 10.0,
            "10.00" to 10.0
        ).forEach { (input, expected) ->
            assertEquals(expected, parseSimpleRating(input)!!, 0.0)
        }
    }

    @Test
    fun rejectsOutOfRangeNonFiniteAndOverPreciseValues() {
        listOf("-0.01", "10.01", "8.999", "texto", "NaN", "Infinity").forEach { input ->
            assertNull(input, parseSimpleRating(input))
        }
    }

    @Test
    fun canonicalScoreFormattingKeepsHundredthsWithoutFloatingPointNoise() {
        assertEquals("8.37", formatRatingInput(8.37))
        assertEquals("10", formatRatingInput(10.0))
        assertEquals("", formatRatingInput(Double.NaN))
    }
}
