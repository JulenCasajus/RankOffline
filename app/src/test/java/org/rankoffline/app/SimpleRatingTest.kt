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
}
