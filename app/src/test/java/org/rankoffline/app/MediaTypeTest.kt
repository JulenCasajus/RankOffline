package org.rankoffline.app

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MediaTypeTest {
    @Test
    fun persistedValuesAreStableAndIndependentOfEnumOrder() {
        assertEquals(
            mapOf(
                MediaType.ANIME to "anime",
                MediaType.MANGA to "manga",
                MediaType.SERIES to "series",
                MediaType.MOVIE to "movie",
                MediaType.BOOK to "book"
            ),
            MediaType.entries.associateWith(MediaType::persistedValue)
        )
    }

    @Test
    fun missingUnknownOrMalformedValuesFallBackToAnime() {
        assertSame(MediaType.ANIME, MediaType.fromPersistedValue(null))
        assertSame(MediaType.ANIME, MediaType.fromPersistedValue("podcast"))
        assertSame(MediaType.ANIME, MediaType.fromPersistedValue(" MOVIE "))
        assertSame(MediaType.ANIME, resolveActiveMediaType(null))
    }

    @Test
    fun everyStableValueRoundTrips() {
        MediaType.entries.forEach { mediaType ->
            assertSame(mediaType, MediaType.fromPersistedValue(mediaType.persistedValue))
            assertSame(mediaType, resolveActiveMediaType(mediaType.persistedValue))
        }
    }

    @Test
    fun appSettingsDefaultToAnime() {
        assertSame(MediaType.ANIME, AppSettingsState().activeMediaType)
    }

    @Test
    fun preferenceUpdateUsesStableValueAndUpdatesOnlyActiveMediaState() {
        val initial = AppSettingsState(showImages = true, stickyScore = false)
        val updated = initial.withActiveMediaType(MediaType.BOOK)

        assertEquals("book", updated.activeMediaType.persistedValue)
        assertSame(MediaType.BOOK, updated.activeMediaType)
        assertEquals(initial.copy(activeMediaType = MediaType.BOOK), updated)
    }

    @Test
    fun everyMediaTypeHasALabelAndTheProductAccent() {
        assertEquals(R.string.media_anime, MediaType.ANIME.labelResource)
        assertEquals(R.string.media_manga, MediaType.MANGA.labelResource)
        assertEquals(R.string.media_series, MediaType.SERIES.labelResource)
        assertEquals(R.string.media_movies, MediaType.MOVIE.labelResource)
        assertEquals(R.string.media_books, MediaType.BOOK.labelResource)

        assertEquals(Color(0xFF25D6E5), MediaType.ANIME.accentColor)
        assertEquals(Color(0xFFE04BCF), MediaType.MANGA.accentColor)
        assertEquals(Color(0xFFE6E530), MediaType.SERIES.accentColor)
        assertEquals(Color(0xFF2E2EDE), MediaType.MOVIE.accentColor)
        assertEquals(Color(0xFF27CF24), MediaType.BOOK.accentColor)
    }
}
