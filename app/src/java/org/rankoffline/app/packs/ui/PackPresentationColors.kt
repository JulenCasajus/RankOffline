package org.rankoffline.app

/** Manifest accent #RRGGBB. A malformed optional visual value falls back to the app theme. */
internal fun packAccentArgbOrNull(value: String): Int? {
    if (!value.matches(Regex("#[0-9A-Fa-f]{6}"))) return null
    val rgb = value.substring(1).toIntOrNull(16) ?: return null
    return (0xFF000000L or rgb.toLong()).toInt()
}
