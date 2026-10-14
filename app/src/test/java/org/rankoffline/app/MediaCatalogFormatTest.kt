package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCatalogFormatTest {
    @Test
    fun validManifestAndRecordParseWithStableDefaults() {
        val manifest = parseMediaCatalogManifest(
            """{"formatVersion":1,"mediaType":"movie","provider":"tmdb","catalogVersion":"v1","sha256":"${"0".repeat(64)}","itemCount":1}"""
        )
        val record = parseMediaCatalogRecord(
            """{"id":"movie:tmdb:550","mediaType":"movie","provider":"tmdb","providerId":"550","title":"Fight Club"}"""
        )

        assertEquals(MediaType.MOVIE, validateMediaCatalogManifest(manifest))
        validateMediaCatalogRecord(record, manifest)
        assertEquals(emptyList<String>(), record.genres)
        assertEquals(emptyList<MediaCatalogCredit>(), record.credits)
        assertEquals(emptyList<MediaCatalogAlias>(), record.aliases)
    }

    @Test
    fun animeCatalogIsRejectedByGenericFormat() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateMediaCatalogManifest(manifest(mediaType = "anime"))
        }

        assertTrue(exception.message.orEmpty().contains("anime"))
    }

    @Test
    fun manifestAndRecordMediaOrProviderMismatchIsRejected() {
        val manifest = manifest(mediaType = "movie", provider = "tmdb")

        assertThrows(IllegalArgumentException::class.java) {
            validateMediaCatalogRecord(record(mediaType = "book", provider = "tmdb"), manifest)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateMediaCatalogRecord(record(provider = "other"), manifest)
        }
    }

    @Test
    fun mediaPrefixMismatchIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validateMediaCatalogRecord(record(id = "book:openlibrary:OL1W"), manifest())
        }
    }

    @Test
    fun nonFinitePopularityIsRejected() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                validateMediaCatalogRecord(record(popularity = value), manifest())
            }
        }
    }

    @Test
    fun unknownJsonFieldsAreIgnored() {
        val manifest = parseMediaCatalogManifest(
            """{"formatVersion":1,"mediaType":"movie","provider":"tmdb","catalogVersion":"v1","sha256":"${"0".repeat(64)}","itemCount":0,"future":true}"""
        )

        assertEquals(MediaType.MOVIE, validateMediaCatalogManifest(manifest))
    }

    private fun manifest(
        mediaType: String = "movie",
        provider: String = "tmdb"
    ) = MediaCatalogManifest(1, mediaType, provider, "v1", "0".repeat(64), 1)

    private fun record(
        id: String = "movie:tmdb:550",
        mediaType: String = "movie",
        provider: String = "tmdb",
        popularity: Double? = 1.0
    ) = MediaCatalogRecord(id, mediaType, provider, "550", "Title", popularity = popularity)
}
