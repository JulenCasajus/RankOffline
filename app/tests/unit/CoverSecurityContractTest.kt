package org.rankoffline.app

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class CoverSecurityContractTest {
    @Test fun templateVariablesAreEncodedButPathSeparatorsAreRejected() {
        assertEquals("hello%20world", encodeUrlComponent("hello world"))
        assertNull(encodeUrlComponent("../escape"))
        assertNull(encodeUrlComponent("a/b"))
    }

    @Test fun imageUrlsRequireHttpsAllowlistedHostAndSafePath() {
        val hosts = setOf("images.example.com")
        assertTrue(isSafeHttpsUri(URI("https://images.example.com/a.jpg"), hosts))
        assertFalse(isSafeHttpsUri(URI("http://images.example.com/a.jpg"), hosts))
        assertFalse(isSafeHttpsUri(URI("https://evil.example/a.jpg"), hosts))
        assertFalse(isSafeHttpsUri(URI("https://images.example.com/a/../b.jpg"), hosts))
    }

    @Test fun forbiddenNotFoundAndGoneAreDefinitiveAbsence() {
        assertEquals(CoverTransferResult.NOT_FOUND, coverTransferFailureForHttpStatus(403))
        assertEquals(CoverTransferResult.NOT_FOUND, coverTransferFailureForHttpStatus(404))
        assertEquals(CoverTransferResult.NOT_FOUND, coverTransferFailureForHttpStatus(410))
        listOf(408, 429, 500, 503).forEach {
            assertEquals(CoverTransferResult.TEMPORARY_ERROR, coverTransferFailureForHttpStatus(it))
        }
    }

    @Test fun blockedRedirectsAndExcessiveHopsAreDefinitiveAbsence() {
        val hosts = setOf("covers.openlibrary.org", "archive.org")
        assertEquals(CoverTransferResult.NOT_FOUND,
            coverRedirectPolicyFailure(URI("https://ia800100.us.archive.org/cover.jpg"), hosts, 2))
        assertEquals(CoverTransferResult.NOT_FOUND,
            coverRedirectPolicyFailure(URI("http://archive.org/cover.jpg"), hosts, 1))
        assertEquals(CoverTransferResult.NOT_FOUND,
            coverRedirectPolicyFailure(URI("https://archive.org/cover.jpg"), hosts, 4))
        assertNull(coverRedirectPolicyFailure(URI("https://archive.org/cover.jpg"), hosts, 3))
    }

    @Test fun redirectHostsStayAttachedToTheCandidateSource() {
        val base = TestFixtures.manifest()
        val source = base.coverSources.single()
        val first = source.copy(id = "first", allowedHosts = setOf("images.example.com", "first.example.com"))
        val second = source.copy(id = "second", allowedHosts = setOf("images.example.com", "second.example.com"))
        val provider = DeclarativePackCoverProvider { base.copy(coverSources = listOf(first, second)) }
        val ref = """{"schemaVersion":1,"sourceId":"first","identity":"x","urls":["https://images.example.com/x.jpg"]}"""
        val item = MediaItem(base.packId, "x", "X", provider = "fixture", remoteCoverRef = ref)
        val candidate = provider.resolveImageCandidates(item).single()
        assertEquals(first.allowedHosts, candidate.allowedHosts)
        assertTrue(isSafeHttpsUri(URI("https://first.example.com/x.jpg"), candidate.allowedHosts))
        assertFalse(isSafeHttpsUri(URI("https://second.example.com/x.jpg"), candidate.allowedHosts))
    }

    @Test fun metadataCandidateKeepsOnlyItsOwnRedirectHosts() = runBlocking {
        val uri = URI("https://images.example.com/x.jpg")
        var observed: CoverImageCandidate? = null
        progressiveCoverDownload(emptyList(), listOf("source-a"), {
            CoverMetadataResolution(listOf(uri), allowedHosts = setOf("images.example.com", "a.example.com"))
        }) { candidate ->
            observed = candidate
            CoverDownloadResult.NotFound
        }
        assertEquals(setOf("images.example.com", "a.example.com"), observed?.allowedHosts)
        assertFalse(isSafeHttpsUri(URI("https://b.example.com/x.jpg"), requireNotNull(observed).allowedHosts))
    }

    @Test fun retryAfterAcceptsSecondsAndRfcDateWithSafetyBound() {
        assertEquals(2_000L, retryAfterDelayMillis("2", 0L))
        assertEquals(60_000L, retryAfterDelayMillis("999999", 0L))
        assertEquals(1_000L, retryAfterDelayMillis("Thu, 1 Jan 1970 00:00:01 GMT", 0L))
        assertNull(retryAfterDelayMillis("invalid", 0L))
    }

    @Test fun declarativeProviderUsesManifestTemplatesWithoutProviderSpecificCode() {
        val provider = DeclarativePackCoverProvider { TestFixtures.manifest() }
        val item = MediaItem(PackId(11), "x", "X", provider = "fixture",
            providerId = "id", remoteCoverRef = "cover id")
        assertEquals("https://images.example.com/cover%20id.jpg",
            provider.resolveCandidates(item).single().toString())
    }

    @Test fun providerRejectsPackMismatchUnknownProviderAndUnsafeStructuredUrl() {
        val provider = DeclarativePackCoverProvider { TestFixtures.manifest() }
        assertTrue(provider.resolveCandidates(MediaItem(PackId(12), "x", "X", provider = "fixture",
            remoteCoverRef = "id")).isEmpty())
        assertTrue(provider.resolveCandidates(MediaItem(PackId(11), "x", "X", provider = "unknown",
            remoteCoverRef = "id")).isEmpty())
        val unsafe = """{"schemaVersion":1,"sourceId":"fixture_cover","identity":"x","urls":["https://evil.example/a.jpg"]}"""
        assertTrue(provider.resolveCandidates(MediaItem(PackId(11), "x", "X", provider = "fixture",
            remoteCoverRef = unsafe)).isEmpty())
    }

    @Test fun metadataReferencesRequireCanonicalPositiveNumericValues() {
        assertEquals(mapOf("first" to "1", "second" to "2"),
            parseMetadataReference("cover:v1|first=1|second=2"))
        assertNull(parseMetadataReference("cover:v1|first=01"))
        assertNull(parseMetadataReference("cover:v1|first=-1"))
        assertNull(parseMetadataReference("cover:v1|first=1|first=2"))
    }

    @Test fun coverStorageIdentityIsQualifiedAndUnambiguous() {
        val first = CoverStorageIdentity(PackId(1), "a:b", CatalogEntryKind.ITEM)
        val second = CoverStorageIdentity(PackId(2), "a:b", CatalogEntryKind.ITEM)
        assertFalse(first.cacheIdentity() == second.cacheIdentity())
        assertFalse(first.cacheIdentity() == CoverStorageIdentity(PackId(1), "a:b",
            CatalogEntryKind.GROUP).cacheIdentity())
    }

    @Test fun downloadDescriptorAndRegistryRejectUnsafeOrDuplicateEntries() {
        val descriptor = PackDownloadDescriptor(TestFixtures.manifest(),
            URI("https://downloads.example.com/pack.pack"), 10, "a".repeat(64), "fixture")
        val registry = PackSourceRegistry()
        registry.replace(listOf(descriptor))
        assertEquals(descriptor, registry.descriptor(PackId(11)))
        assertThrows(IllegalArgumentException::class.java) { registry.replace(listOf(descriptor, descriptor)) }
        assertThrows(IllegalArgumentException::class.java) {
            PackDownloadDescriptor(TestFixtures.manifest(), URI("http://example.com/pack.pack"),
                10, "a".repeat(64), "fixture")
        }
    }
}
