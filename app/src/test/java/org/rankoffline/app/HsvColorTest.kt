package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HsvColorTest {
    @Test
    fun primaryColorsRoundTripThroughHsv() {
        listOf("#FF0000", "#00FF00", "#0000FF", "#19D5E5", "#FFFFFF", "#000000")
            .forEach { hex ->
                assertEquals(hex, HsvColor.fromHex(hex)?.toHex())
            }
    }

    @Test
    fun hueWrapsAndChannelsRemainBounded() {
        assertEquals("#FF0000", HsvColor(360f, 1f, 1f).toHex())
        assertEquals("#FF0000", HsvColor(-360f, 2f, 2f).toHex())
    }

    @Test
    fun partialOrInvalidHexDoesNotReplaceTheLastValidColor() {
        assertNull(HsvColor.fromHex("#12"))
        assertNull(HsvColor.fromHex("not-a-color"))
    }
}
