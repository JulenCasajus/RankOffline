package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingConfigurationTest {
    @Test
    fun ratingStopsContainTwentyOneValuesAndNineteenInteriorTicks() {
        assertStops(RATING_MIN, RATING_MAX, RATING_STEP, 21, 19, 0.5, 9.5)
        assertEquals(19, RATING_SLIDER_STEPS)
    }

    @Test
    fun bonusStopsContainElevenValuesAndNineInteriorTicksWithoutFloatArtifacts() {
        assertStops(BONUS_MIN, BONUS_MAX, BONUS_STEP, 11, 9, 0.1, 0.9)
        assertEquals(9, BONUS_SLIDER_STEPS)
        assertEquals(0.3, snapBonusValue(0.29999998)!!, 0.0)
        assertEquals("0.3", snapBonusValue(0.29999998).toString())
    }

    @Test
    fun weightStopsContainTwentyOneValuesAndNineteenInteriorTicks() {
        assertStops(WEIGHT_MIN, WEIGHT_MAX, WEIGHT_STEP, 21, 19, 5.0, 95.0)
        assertEquals(19, WEIGHT_SLIDER_STEPS)
        listOf(0.0, 5.0, 35.0, 95.0, 100.0).forEach {
            assertEquals(it, snapWeight(it)!!, 0.0)
        }
        assertFalse(snapWeight(33.0) == 33.0)
        assertEquals(35.0, snapWeight(33.0)!!, 0.0)
    }

    @Test
    fun hexColorsNormalizeAndOnlyValidApplyChangesCategory() {
        val original = RatingCategory("id", "Name", "Description", 10.0, "#19D5E5", 0)

        assertEquals("#A1B2C3", normalizeHexColor("#a1b2c3"))
        assertEquals("#A1B2C3", original.withHexColor("#a1b2c3")!!.color)
        assertEquals("#FF675D", original.withHexColor("#FF675D")!!.color)
        listOf("A1B2C3", "#12345", "#GG0000", "", "#12345678").forEach {
            assertNull(normalizeHexColor(it))
            assertNull(original.withHexColor(it))
        }
        assertEquals("#19D5E5", original.color)
    }

    private fun assertStops(
        min: Double,
        max: Double,
        step: Double,
        total: Int,
        interiorCount: Int,
        firstInterior: Double,
        lastInterior: Double
    ) {
        val values = discreteValues(min, max, step)
        val ticks = interiorDiscreteValues(min, max, step)
        assertEquals(total, values.size)
        assertEquals(interiorCount, ticks.size)
        assertEquals(firstInterior, ticks.first(), 0.0)
        assertEquals(lastInterior, ticks.last(), 0.0)
        assertFalse(ticks.contains(min))
        assertFalse(ticks.contains(max))
        assertTrue(ticks.zipWithNext().all { (a, b) -> kotlin.math.abs((b - a) - step) < 1e-9 })
    }
}
