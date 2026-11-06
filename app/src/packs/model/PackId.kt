package org.rankoffline.app

/** Stable, positive 64-bit identifier. 0 is reserved for the virtual RankOfflineAll scope.
 * Names of packs and publishers are presentation, never identity.
 */
@JvmInline
value class PackId(val value: Long) {
    init { require(value > 0L) { "A physical pack ID must be positive: $value" } }

    /** Canonical on-disk name for a physical installation under files/packs/. */
    fun directoryName(): String = "pack-$value"

    companion object {
        /** Only canonical pack-<positive decimal> directory names are valid. */
        fun fromDirectoryName(name: String): PackId? {
            if (!name.startsWith("pack-")) return null
            return runCatching { parse(name.removePrefix("pack-")) }.getOrNull()
        }

        fun parse(value: String): PackId {
            val number = value.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid numeric pack ID: $value")
            require(number > 0L && value == number.toString()) {
                "Pack ID must be a canonical positive decimal: $value"
            }
            return PackId(number)
        }
        /** A JSON manifest must contain an integer NUMBER, never a quoted string or a decimal. */
        fun fromJson(value: Any): PackId {
            require(value is Int || value is Long) { "packId must be a JSON integer" }
            return PackId((value as Number).toLong())
        }
    }

    override fun toString(): String = value.toString()
}
