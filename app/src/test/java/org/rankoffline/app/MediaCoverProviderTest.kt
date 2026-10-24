package org.rankoffline.app

import java.net.URI
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

    @Test
    fun customPackTemplateResolvesWithoutProviderSpecificKotlinCode() {
        val manifest = manifest("https://images.example.org/posters/{providerId}/{reference}")
        val resolver = DeclarativePackCoverProvider { manifest }

        assertEquals(
            "https://images.example.org/posters/42/cover%20one.webp",
            resolver.resolve(item("custom", "42", "cover one.webp"))?.toASCIIString()
        )
    }

    @Test
    fun customPackDirectFallbacksAreOrderedAndHostRestricted() {
        val manifest = manifest("https://images.example.org/{reference}")
        val resolver = DeclarativePackCoverProvider { manifest }
        val reference = """{
            "schemaVersion":1,
            "sourceId":"custom_images",
            "identity":"stable-cover-42",
            "urls":[
                "https://images.example.org/primary.webp",
                "https://images.example.org/fallback.webp",
                "https://evil.example/fail.webp"
            ]
        }""".trimIndent()

        assertEquals(
            listOf(
                "https://images.example.org/primary.webp",
                "https://images.example.org/fallback.webp"
            ),
            resolver.resolveCandidates(item("custom", "42", reference)).map(URI::toASCIIString)
        )
    }

    @Test
    fun unsafePackTemplatesAndPrivateDestinationsAreRejected() {
        assertFalse(isSafeHttpsUri(URI.create("http://images.example.org/cover"), setOf("images.example.org")))
        assertFalse(isSafeHttpsUri(URI.create("https://localhost/cover"), setOf("localhost")))
        assertFalse(isSafeHttpsUri(URI.create("https://127.0.0.1/cover"), setOf("127.0.0.1")))
        assertFalse(isSafeHttpsUri(URI.create("https://images.example.org/../cover"), setOf("images.example.org")))
        assertFalse(isSafeHttpsUri(URI.create("https://evil.example/cover"), setOf("images.example.org")))
        listOf(
            "http://images.example.org/{reference}",
            "https://localhost/{reference}",
            "https://127.0.0.1/{reference}",
            "https://images.example.org/../{reference}",
            "https://images.example.org/{unsupported}"
        ).forEach { template ->
            assertTrue(runCatching {
                validatePackCoverUrlTemplate(template, setOf("images.example.org"))
            }.isFailure)
        }
    }

    private fun manifest(template: String): PackManifest = PackManifest(
        schemaVersion = 1,
        packId = OfficialPackIds.Movies,
        displayName = "Movies",
        version = "fixture",
        minRankOfflineVersion = "0.2.0",
        provider = PackProvider("custom", "Custom"),
        coverSources = listOf(PackCoverSource(
            id = "custom_images",
            providerId = "custom",
            urlTemplates = listOf(template),
            allowedHosts = setOf("images.example.org"),
            attributionName = "Example",
            attributionUrl = "https://example.org"
        )),
        catalog = listOf("catalog/items.jsonl.gzip"),
        groups = emptyList(),
        covers = emptyList(),
        capabilities = PackCapabilities(true, true, true, true, false, true),
        filters = emptyList(),
        subtitle = emptyList(),
        merge = PackMergeDefinition(false, PackMergeStrategy.NONE, null),
        defaults = PackDefaults(true, false, false, "simple"),
        files = listOf(PackManifestFile("catalog/items.jsonl.gzip", 1, "0".repeat(64)))
    )

    private fun item(provider: String, providerId: String, reference: String?) = MediaItem(
        id = "movie:test:$providerId",
        mediaType = MediaType.MOVIE,
        provider = provider,
        providerId = providerId,
        title = "Title",
        remoteCoverRef = reference
    )
}
