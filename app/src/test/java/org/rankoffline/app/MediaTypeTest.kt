package org.rankoffline.app

import androidx.compose.ui.graphics.Color
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTypeTest {
    @Test
    fun searchNavigationUsesGenericLabelOnlyForTheTrueZeroPackState() {
        assertEquals(R.string.nav_search, searchNavigationLabelResource(true, MediaType.MOVIE))
        assertEquals(
            MediaType.MOVIE.labelResource,
            searchNavigationLabelResource(false, MediaType.MOVIE)
        )
    }

    @Test
    fun unavailableCategoryRoutesToManagementInsteadOfBrokenContent() {
        val installed = setOf(MediaType.ANIME, MediaType.BOOK)

        assertEquals(CategorySelectionAction.SWITCH, categorySelectionAction(MediaType.BOOK, installed))
        assertEquals(
            CategorySelectionAction.MANAGE_DOWNLOAD,
            categorySelectionAction(MediaType.MOVIE, installed)
        )
    }
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
            assertSame(mediaType, resolveActiveMediaType(mediaType.toPackId().value))
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
        assertEquals(
            initial.copy(
                activeMediaType = MediaType.BOOK,
                showImages = DefaultSettings.mediaSettings(MediaType.BOOK).showImages,
                stickyScore = DefaultSettings.mediaSettings(MediaType.BOOK).stickyScore
            ),
            updated
        )
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
        assertEquals(Color(0xFFEF4444), MediaType.MOVIE.accentColor)
        assertEquals(Color(0xFF27CF24), MediaType.BOOK.accentColor)
    }

    @Test
    fun mediaScreenTitleJoinsTheScreenAndPluralCategoryWithoutWhitespace() {
        assertEquals("RankOfflineMangas", mediaScreenTitle("RankOffline", "Mangas", Color.Magenta).text)
        assertEquals("MiRankingLibros", mediaScreenTitle("MiRanking", "Libros", Color.Green).text)
        assertEquals("AjustesPelículas", mediaScreenTitle("Ajustes", "Películas", Color.Blue).text)
    }

    @Test
    fun mediaMenuShowsAllCategoriesInLocalizedAlphabeticalOrder() {
        val labels = mapOf(
            MediaType.ANIME to "Animes",
            MediaType.MANGA to "Mangas",
            MediaType.SERIES to "Series",
            MediaType.MOVIE to "Películas",
            MediaType.BOOK to "Libros"
        )

        assertEquals(
            listOf(MediaType.ANIME, MediaType.BOOK, MediaType.MANGA, MediaType.MOVIE, MediaType.SERIES),
            mediaTypeMenuOptions(labels, Locale.forLanguageTag("es"))
        )
    }

    @Test
    fun installedMenuContainsOnlyReadyPacksAndAllowsAnEmptyList() {
        val labels = MediaType.entries.associateWith { it.name.lowercase() }

        assertTrue(installedMediaTypeMenuOptions(labels, Locale.ENGLISH, emptySet()).isEmpty())
        assertEquals(
            listOf(MediaType.MOVIE),
            installedMediaTypeMenuOptions(labels, Locale.ENGLISH, setOf(MediaType.MOVIE))
        )
        assertEquals(
            MediaType.entries.toSet(),
            installedMediaTypeMenuOptions(labels, Locale.ENGLISH, MediaType.entries.toSet()).toSet()
        )
    }
}
