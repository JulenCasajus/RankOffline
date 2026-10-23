package org.rankoffline.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real app database when locally installed production packs are present. A normal
 * clean instrumentation run has no packs and therefore skips these environment-specific checks.
 */
@RunWith(AndroidJUnit4::class)
class InstalledPackMergeSmokeTest {
    @Test
    fun productionSeriesAndMovieRelationshipsMergeThroughRepository() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = AnimeDatabase.getInstance(context)
        val seriesGroupId = "series:tmdb:2734"
        val movieGroupId = "movie:tmdb:collection:10194"
        assumeTrue(database.mediaGroupDao().group(seriesGroupId) != null)
        assumeTrue(database.mediaGroupDao().group(movieGroupId) != null)

        assertEquals(28, database.mediaGroupDao().group(seriesGroupId)?.memberCount)
        assertEquals(5, database.mediaGroupDao().group(movieGroupId)?.memberCount)

        val repository = GenericMediaRepository(database)
        val series = repository.search(
            MediaType.SERIES,
            "Law & Order: Special Victims Unit",
            CatalogSortOrder.POPULARITY,
            GenericMediaFilters(),
            mergeEnabled = true
        )
        assertTrue(series.any { it is MediaListEntry.Group && it.id == seriesGroupId })
        assertFalse(series.any { it.id.startsWith("series:tmdb:2734:season:") })

        val movies = repository.search(
            MediaType.MOVIE,
            "Toy Story",
            CatalogSortOrder.POPULARITY,
            GenericMediaFilters(),
            mergeEnabled = true
        )
        assertTrue(movies.any { it is MediaListEntry.Group && it.id == movieGroupId })
        assertFalse(movies.any { it.id in setOf(
            "movie:tmdb:862", "movie:tmdb:863", "movie:tmdb:10193",
            "movie:tmdb:301528", "movie:tmdb:1084244"
        ) })
    }

}
