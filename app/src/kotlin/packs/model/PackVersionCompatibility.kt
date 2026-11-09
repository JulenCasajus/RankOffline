package org.rankoffline.app

/** Compare SemVer including prerelease identifiers; build metadata does not affect ordering. */
internal fun isCompatiblePackVersion(minimum: String, current: String): Boolean {
    val required = PackVersion.parse(minimum) ?: return false
    val running = PackVersion.parse(current) ?: return false
    val ordered = compareValuesBy(running, required, { it.major }, { it.minor }, { it.patch })
    if (ordered != 0) return ordered > 0
    if (running.prerelease == null) return true
    if (required.prerelease == null) {
        // A debug APK is built from this exact release core and must accept its packs.
        return running.prerelease == "debug" || running.prerelease.startsWith("debug.")
    }
    val left = running.prerelease.split('.')
    val right = required.prerelease.split('.')
    for (index in 0 until minOf(left.size, right.size)) {
        val a = left[index]; val b = right[index]
        val aNumeric = a.all(Char::isDigit)
        val bNumeric = b.all(Char::isDigit)
        val compare = when {
            aNumeric && bNumeric -> java.math.BigInteger(a).compareTo(java.math.BigInteger(b))
            aNumeric -> -1
            bNumeric -> 1
            else -> a.compareTo(b)
        }
        if (compare != 0) return compare > 0
    }
    return left.size >= right.size
}

private data class PackVersion(
    val major: Int, val minor: Int, val patch: Int, val prerelease: String?
) {
    companion object {
        private val pattern = Regex(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
        )
        fun parse(value: String): PackVersion? {
            if (value.length > 80) return null
            val match = pattern.matchEntire(value) ?: return null
            val numbers = (1..3).map { match.groupValues[it].toIntOrNull() ?: return null }
            val prerelease = match.groupValues[4].ifBlank { null }
            // SemVer forbids numeric prerelease components with leading zeros.
            if (prerelease?.split('.')?.any { token ->
                    token.length > 1 && token.first() == '0' && token.all(Char::isDigit)
                } == true) return null
            return PackVersion(numbers[0], numbers[1], numbers[2], prerelease)
        }
    }
}
