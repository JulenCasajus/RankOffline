package org.rankoffline.app

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color

@get:StringRes
val MediaType.labelResource: Int
    get() = when (this) {
        MediaType.ANIME -> R.string.media_anime
        MediaType.MANGA -> R.string.media_manga
        MediaType.SERIES -> R.string.media_series
        MediaType.MOVIE -> R.string.media_movies
        MediaType.BOOK -> R.string.media_books
    }

val MediaType.accentColor: Color
    get() = when (this) {
        MediaType.ANIME -> Color(0xFF25D6E5)
        MediaType.MANGA -> Color(0xFFE04BCF)
        MediaType.SERIES -> Color(0xFFE6E530)
        MediaType.MOVIE -> Color(0xFF2E2EDE)
        MediaType.BOOK -> Color(0xFF27CF24)
    }
