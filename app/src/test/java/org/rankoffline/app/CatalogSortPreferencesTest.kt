package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogSortPreferencesTest {
    @Test
    fun missingPreferencesDefaultEveryMediaToMostPopularFirst() {
        val resolved = resolveCatalogSortOrders(null)

        assertEquals(MediaType.entries.toSet(), resolved.keys)
        MediaType.entries.forEach { mediaType ->
            assertEquals(CatalogSortOrder.POPULARITY, resolved.getValue(mediaType))
        }
    }

    @Test
    fun eachMediaSortAndDirectionRoundTripsIndependently() {
        val selected = mapOf(
            MediaType.ANIME to CatalogSortOrder.TITLE_ASC,
            MediaType.MANGA to CatalogSortOrder.TITLE_DESC,
            MediaType.SERIES to CatalogSortOrder.POPULARITY_REVERSE,
            MediaType.MOVIE to CatalogSortOrder.POPULARITY,
            MediaType.BOOK to CatalogSortOrder.TITLE_ASC
        )

        assertEquals(selected, resolveCatalogSortOrders(encodeCatalogSortOrders(selected)))
    }

    @Test
    fun malformedAndFutureValuesCannotBreakDefaults() {
        val resolved = resolveCatalogSortOrders(
            setOf(
                "anime=TITLE_DESC",
                "manga=NOT_A_SORT",
                "future=TITLE_ASC",
                "broken"
            )
        )

        assertEquals(CatalogSortOrder.TITLE_DESC, resolved.getValue(MediaType.ANIME))
        assertEquals(CatalogSortOrder.POPULARITY, resolved.getValue(MediaType.MANGA))
        assertEquals(CatalogSortOrder.POPULARITY, resolved.getValue(MediaType.BOOK))
    }
}
