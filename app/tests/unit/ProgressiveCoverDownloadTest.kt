package org.rankoffline.app

import java.net.URI
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProgressiveCoverDownloadTest {
    @Test
    fun firstMetadataSourceSuccessDoesNotResolveSecondSource() = runBlocking {
        val first = URI("https://images.example.com/first.jpg")
        val resolved = mutableListOf<String>()
        val result = progressiveCoverDownload(emptyList(), listOf("first", "second"), { source ->
            resolved += source
            CoverMetadataResolution(listOf(first))
        }) { CoverDownloadResult.Downloaded }

        assertEquals(CoverDownloadResult.Downloaded, result)
        assertEquals(listOf("first"), resolved)
    }

    @Test
    fun firstMetadataNotFoundFallsBackToSecondSource() = runBlocking {
        val second = URI("https://images.example.com/second.jpg")
        val resolved = mutableListOf<String>()
        val result = progressiveCoverDownload(emptyList(), listOf("first", "second"), { source ->
            resolved += source
            if (source == "first") CoverMetadataResolution()
            else CoverMetadataResolution(listOf(second))
        }) { CoverDownloadResult.Downloaded }

        assertEquals(CoverDownloadResult.Downloaded, result)
        assertEquals(listOf("first", "second"), resolved)
    }

    @Test
    fun imageNotFoundFromFirstSourceFallsBackToSecondSource() = runBlocking {
        val first = URI("https://images.example.com/missing.jpg")
        val second = URI("https://images.example.com/found.jpg")
        val resolved = mutableListOf<String>()
        val result = progressiveCoverDownload(emptyList(), listOf("first", "second"), { source ->
            resolved += source
            CoverMetadataResolution(listOf(if (source == "first") first else second))
        }) { if (it.uri == first) CoverDownloadResult.NotFound else CoverDownloadResult.Downloaded }

        assertEquals(CoverDownloadResult.Downloaded, result)
        assertEquals(listOf("first", "second"), resolved)
    }

    @Test
    fun forbiddenImageFallsBackBeforeDefinitiveAbsence() = runBlocking {
        val forbidden = URI("https://images.example.com/forbidden.jpg")
        val fallback = URI("https://images.example.com/fallback.jpg")
        val result = progressiveCoverDownload(emptyList(), listOf("first", "second"), { source ->
            CoverMetadataResolution(listOf(if (source == "first") forbidden else fallback))
        }) { if (it.uri == forbidden) CoverDownloadResult.NotFound else CoverDownloadResult.Downloaded }

        assertEquals(CoverTransferResult.NOT_FOUND, coverTransferFailureForHttpStatus(403))
        assertEquals(CoverDownloadResult.Downloaded, result)
    }

    @Test
    fun unauthorizedRedirectFallsBackBeforePersistingAbsence() = runBlocking {
        val first = URI("https://covers.openlibrary.org/first.jpg")
        val fallback = URI("https://images.example.com/fallback.jpg")
        val requested = mutableListOf<URI>()
        val result = progressiveCoverDownload(
            listOf(
                CoverImageCandidate(first, setOf("covers.openlibrary.org", "archive.org")),
                CoverImageCandidate(fallback, setOf("images.example.com"))
            ), emptyList<String>(), { CoverMetadataResolution() }
        ) { candidate ->
            requested += candidate.uri
            if (candidate.uri == first) {
                assertEquals(CoverTransferResult.NOT_FOUND, coverRedirectPolicyFailure(
                    URI("https://ia800100.us.archive.org/first.jpg"), candidate.allowedHosts, 2))
                CoverDownloadResult.NotFound
            } else CoverDownloadResult.Downloaded
        }

        assertEquals(CoverDownloadResult.Downloaded, result)
        assertEquals(listOf(first, fallback), requested)
    }

    @Test
    fun rateLimitWinsOverNotFoundWhenNoSourceSucceeds() = runBlocking {
        val result = progressiveCoverDownload(emptyList(), listOf("first", "second"), { source ->
            if (source == "first") CoverMetadataResolution(failure = CoverDownloadResult.TemporaryError)
            else CoverMetadataResolution()
        }) { CoverDownloadResult.NotFound }

        assertEquals(CoverTransferResult.TEMPORARY_ERROR, coverTransferFailureForHttpStatus(429))
        assertEquals(2_000L, retryAfterDelayMillis("2", 0L))
        assertEquals(CoverDownloadResult.TemporaryError, result)
    }

    @Test
    fun onlyNotFoundAndGoneResponsesProduceNotFound() = runBlocking {
        val missing = URI("https://images.example.com/missing.jpg")
        val gone = URI("https://images.example.com/gone.jpg")
        val result = progressiveCoverDownload(emptyList(), listOf("first", "second"), { source ->
            CoverMetadataResolution(listOf(if (source == "first") missing else gone))
        }) { CoverDownloadResult.NotFound }

        assertEquals(CoverTransferResult.NOT_FOUND, coverTransferFailureForHttpStatus(404))
        assertEquals(CoverTransferResult.NOT_FOUND, coverTransferFailureForHttpStatus(410))
        assertEquals(CoverDownloadResult.NotFound, result)
        val key = CatalogEntryKey(PackId(1), "missing", CatalogEntryKind.ITEM)
        val progress = coverProgressSnapshot(PackId(1), CoverDownloadStatus.COMPLETED, 1,
            mapOf(key to result), emptySet())
        assertEquals(1, progress.unavailable)
        assertEquals(0, progress.errors)
    }

    @Test
    fun timeoutsRemainRetryableAcrossThreeLogicalAttempts() = runBlocking {
        var requests = 0
        repeat(3) {
            val result = progressiveCoverDownload(emptyList(), listOf("first", "second"), { source ->
                CoverMetadataResolution(listOf(URI("https://images.example.com/$source.jpg")))
            }) {
                requests++
                CoverDownloadResult.NetworkError
            }
            assertEquals(CoverDownloadResult.NetworkError, result)
        }
        assertEquals(6, requests)
    }

    @Test
    fun secondUrlFromSameSourcePreventsNextSourceLookup() = runBlocking {
        val first = URI("https://images.example.com/first.jpg")
        val second = URI("https://images.example.com/second.jpg")
        val resolved = mutableListOf<String>()
        val result = progressiveCoverDownload(emptyList(), listOf("primary", "fallback"), { source ->
            resolved += source
            CoverMetadataResolution(listOf(first, second))
        }) { if (it.uri == first) CoverDownloadResult.NotFound else CoverDownloadResult.Downloaded }

        assertEquals(CoverDownloadResult.Downloaded, result)
        assertEquals(listOf("primary"), resolved)
    }

    @Test
    fun duplicateUrlsAreRequestedOnlyOnceInFirstSeenOrder() = runBlocking {
        val shared = URI("https://images.example.com/shared.jpg")
        val final = URI("https://images.example.com/final.jpg")
        val requested = mutableListOf<URI>()
        val result = progressiveCoverDownload(listOf(CoverImageCandidate(shared, imageHosts)), listOf("first", "second"), { source ->
            if (source == "first") CoverMetadataResolution(listOf(shared, shared), allowedHosts = imageHosts)
            else CoverMetadataResolution(listOf(shared, final), allowedHosts = imageHosts)
        }) { image ->
            requested += image.uri
            if (image.uri == final) CoverDownloadResult.Downloaded else CoverDownloadResult.NotFound
        }

        assertEquals(CoverDownloadResult.Downloaded, result)
        assertEquals(listOf(shared, final), requested)
    }

    @Test
    fun cancellationDuringMetadataOrDownloadStopsImmediately() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                progressiveCoverDownload(emptyList(), listOf("first", "second"), {
                    throw CancellationException("metadata cancelled")
                }) { CoverDownloadResult.Downloaded }
            }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                progressiveCoverDownload(
                    listOf(CoverImageCandidate(URI("https://images.example.com/first.jpg"), imageHosts)), emptyList<String>(),
                    { CoverMetadataResolution() }
                ) { throw CancellationException("download cancelled") }
            }
        }
    }

    @Test
    fun invalidImageTriesNextCandidate() = runBlocking {
        val invalid = URI("https://images.example.com/invalid.jpg")
        val valid = URI("https://images.example.com/valid.jpg")
        val result = progressiveCoverDownload(listOf(CoverImageCandidate(invalid, imageHosts),
            CoverImageCandidate(valid, imageHosts)), emptyList<String>(),
            { CoverMetadataResolution() }) { image ->
            if (image.uri == invalid) CoverDownloadResult.TemporaryError else CoverDownloadResult.Downloaded
        }

        assertEquals(CoverDownloadResult.Downloaded, result)
    }

    @Test
    fun exhaustedCoverIncrementsErrorsExactlyOnceAfterThirdAttempt() = runBlocking {
        val packId = PackId(1L)
        val key = CatalogEntryKey(packId, "item", CatalogEntryKind.ITEM)
        val outcomes = linkedMapOf<CatalogEntryKey, CoverDownloadResult>()
        val exhausted = hashSetOf<CatalogEntryKey>()
        repeat(3) { attempt ->
            outcomes[key] = progressiveCoverDownload(
                listOf(CoverImageCandidate(URI("https://images.example.com/timeout.jpg"), imageHosts)), emptyList<String>(),
                { CoverMetadataResolution() }
            ) { CoverDownloadResult.NetworkError }
            if (attempt == 2) exhausted += key
            val progress = coverProgressSnapshot(packId, CoverDownloadStatus.RETRYING_COVERS,
                1, outcomes, exhausted)
            assertEquals(if (attempt == 2) 1 else 0, progress.errors)
        }
        assertEquals(1, coverProgressSnapshot(packId, CoverDownloadStatus.COMPLETED,
            1, outcomes, exhausted).errors)
    }

}

private val imageHosts = setOf("images.example.com")
