package org.rankoffline.app

/** A stable media identity. Persist [persistedValue], never the enum ordinal or display label. */
enum class MediaType(val persistedValue: String) {
    ANIME("anime"),
    MANGA("manga"),
    SERIES("series"),
    MOVIE("movie"),
    BOOK("book");

    companion object {
        fun fromPersistedValue(value: String?): MediaType =
            entries.firstOrNull { it.persistedValue == value } ?: ANIME
    }
}
