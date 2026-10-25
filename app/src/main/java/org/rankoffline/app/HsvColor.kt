package org.rankoffline.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class HsvColor(
    val hue: Float,
    val saturation: Float,
    val value: Float
) {
    fun normalized(): HsvColor = HsvColor(
        hue = ((hue % 360f) + 360f) % 360f,
        saturation = saturation.coerceIn(0f, 1f),
        value = value.coerceIn(0f, 1f)
    )

    fun toArgb(): Int {
        val normalized = normalized()
        val chroma = normalized.value * normalized.saturation
        val segment = normalized.hue / 60f
        val x = chroma * (1f - abs(segment % 2f - 1f))
        val (r1, g1, b1) = when (segment.toInt().coerceIn(0, 5)) {
            0 -> Triple(chroma, x, 0f)
            1 -> Triple(x, chroma, 0f)
            2 -> Triple(0f, chroma, x)
            3 -> Triple(0f, x, chroma)
            4 -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val match = normalized.value - chroma
        fun channel(value: Float) = ((value + match) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r1) shl 16) or (channel(g1) shl 8) or channel(b1)
    }

    fun toHex(): String = "#%06X".format(toArgb() and 0x00FF_FFFF)

    companion object {
        fun fromArgb(argb: Int): HsvColor {
            val red = ((argb ushr 16) and 0xFF) / 255f
            val green = ((argb ushr 8) and 0xFF) / 255f
            val blue = (argb and 0xFF) / 255f
            val maximum = max(red, max(green, blue))
            val minimum = min(red, min(green, blue))
            val delta = maximum - minimum
            val hue = when {
                delta == 0f -> 0f
                maximum == red -> 60f * (((green - blue) / delta) % 6f)
                maximum == green -> 60f * (((blue - red) / delta) + 2f)
                else -> 60f * (((red - green) / delta) + 4f)
            }
            return HsvColor(
                hue = if (hue < 0f) hue + 360f else hue,
                saturation = if (maximum == 0f) 0f else delta / maximum,
                value = maximum
            )
        }

        fun fromHex(value: String): HsvColor? {
            val normalized = normalizeHexColor(value) ?: return null
            val rgb = normalized.removePrefix("#").toIntOrNull(16) ?: return null
            return fromArgb((0xFF shl 24) or rgb)
        }
    }
}
