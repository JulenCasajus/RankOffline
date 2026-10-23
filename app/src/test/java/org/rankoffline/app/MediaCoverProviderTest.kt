package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCoverProviderTest {
    private val provider = DefaultMediaCoverProvider()

    @Test
    fun tmdbPosterPathResolvesToW500HttpsUrl() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/abc123.jpg",
            provider.resolve(item("tmdb", "550", "/abc123.jpg"))?.toASCIIString()
        )
    }

    @Test
    fun mangaDexCoverResolvesTo512PixelHttpsUrl() {
        val uuid = "12345678-1234-1234-1234-123456789abc"
        assertEquals(
            "https://uploads.mangadex.org/covers/$uuid/cover.jpg.512.jpg",
            provider.resolve(item("mangadex", uuid, "cover.jpg"))?.toASCIIString()
        )
    }

    @Test
    fun openLibraryCoverUsesLargeImageAndNoDefaultPlaceholder() {
        assertEquals(
            "https://covers.openlibrary.org/b/id/12345-L.jpg?default=false",
            provider.resolve(item("openlibrary", "OL1W", "12345"))?.toASCIIString()
        )
    }

    @Test
    fun openLibraryCandidatesUsePrecomputedIdentifiersInFallbackOrder() {
        val reference = encodeOpenLibraryCoverReference(
            coverId = "12345",
            isbn13 = "978-0-14-032872-1",
            isbn10 = "0140328726",
            editionOlid = "OL7353617M",
            workOlid = "OL45804W"
        )

        assertEquals(
            listOf(
                "https://covers.openlibrary.org/b/id/12345-L.jpg?default=false",
                "https://covers.openlibrary.org/b/isbn/9780140328721-L.jpg?default=false",
                "https://covers.openlibrary.org/b/isbn/0140328726-L.jpg?default=false",
                "https://covers.openlibrary.org/b/olid/OL7353617M-L.jpg?default=false",
                "https://covers.openlibrary.org/b/olid/OL45804W-L.jpg?default=false"
            ),
            provider.resolveCandidates(item("openlibrary", "OL45804W", reference))
                .map { it.toASCIIString() }
        )
    }

    @Test
    fun legacyOpenLibraryReferenceFallsBackToKnownWorkOlid() {
        assertEquals(
            listOf(
                "https://covers.openlibrary.org/b/id/12345-L.jpg?default=false",
                "https://covers.openlibrary.org/b/olid/OL45804W-L.jpg?default=false"
            ),
            provider.resolveCandidates(item("openlibrary", "OL45804W", "12345"))
                .map { it.toASCIIString() }
        )
    }

    @Test
    fun missingReferenceAndUnknownProviderReturnNull() {
        assertNull(provider.resolve(item("tmdb", "550", null)))
        assertNull(provider.resolve(item("unknown", "1", "cover.jpg")))
    }

    @Test
    fun unsafeReferencesCannotSelectAnotherHostOrProtocol() {
        listOf(
            "https://evil.example/cover.jpg",
            "http://evil.example/cover.jpg",
            "../../cover.jpg",
            "javascript:alert(1)"
        ).forEach { reference ->
            assertNull(provider.resolve(item("tmdb", "550", reference)))
        }
    }

    @Test
    fun everyResolvedProviderUrlIsHttpsAndAllowlisted() {
        val uuid = "12345678-1234-1234-1234-123456789abc"
        val resolved = listOf(
            provider.resolve(item("tmdb", "1", "/a.jpg")),
            provider.resolve(item("mangadex", uuid, "b.jpg")),
            provider.resolve(item("openlibrary", "OL1W", "7"))
        ).filterNotNull()

        assertEquals(3, resolved.size)
        assertTrue(resolved.all { it.scheme == "https" })
        assertTrue(resolved.all { it.host in DefaultMediaCoverProvider.ALLOWED_IMAGE_HOSTS })
    }

    @Test
    fun deterministicFileNameIsSafeAndStable() {
        val first = mediaCoverFileName("movie:tmdb:550")

        assertEquals(first, mediaCoverFileName("movie:tmdb:550"))
        assertFalse(first.contains(':'))
        assertTrue(first.matches(Regex("[0-9a-f]{64}\\.cover")))
    }

    private fun item(provider: String, providerId: String, reference: String?) = MediaItem(
        id = "movie:test:$providerId",
        mediaType = MediaType.MOVIE,
        provider = provider,
        providerId = providerId,
        title = "Title",
        remoteCoverRef = reference
    )
}
