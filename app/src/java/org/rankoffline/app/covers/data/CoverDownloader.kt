package org.rankoffline.app

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class CoverTransferResult { DOWNLOADED, NOT_FOUND, NETWORK_ERROR, TEMPORARY_ERROR }

internal fun coverTransferFailureForHttpStatus(status: Int): CoverTransferResult? = when {
    status in 200..299 -> null
    status == 404 || status == 410 -> CoverTransferResult.NOT_FOUND
    else -> CoverTransferResult.TEMPORARY_ERROR
}

fun interface CoverDownloader {
    /** Destination must be a fresh temporary file in the app's private directory. */
    suspend fun download(remoteUri: URI, destination: File): CoverTransferResult
}

/**
 * HTTPS-only, limited-size, non-executing image transfer. Never follows redirects automatically.
 * DNS checks are defense in depth, not an atomic defense against DNS rebinding or hostile proxies.
 */
class SecureCoverDownloader : CoverDownloader {
    override suspend fun download(remoteUri: URI, destination: File): CoverTransferResult =
        withContext(Dispatchers.IO) {
            val host = remoteUri.host?.lowercase()
                ?.takeIf { isSafeHttpsUri(remoteUri, setOf(it)) }
                ?: return@withContext CoverTransferResult.TEMPORARY_ERROR
            var current = remoteUri
            var redirects = 0
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val wait = retryAfterUntil.get() - System.currentTimeMillis()
                    if (wait > 0L) delay(wait)
                    if (!isSafeHttpsUri(current, setOf(host))) {
                        return@withContext CoverTransferResult.TEMPORARY_ERROR
                    }
                    if (!resolvesToPublicAddresses(host)) {
                        return@withContext CoverTransferResult.TEMPORARY_ERROR
                    }

                    val connection = (current.toURL().openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        setRequestProperty("Accept", "image/*")
                        setRequestProperty("User-Agent", "RankOffline")
                    }
                    try {
                        val status = connection.responseCode
                        if (status in REDIRECT_STATUSES) {
                            if (redirects++ >= 3) return@withContext CoverTransferResult.TEMPORARY_ERROR
                            val location = connection.getHeaderField("Location")
                                ?: return@withContext CoverTransferResult.TEMPORARY_ERROR
                            current = runCatching { current.resolve(location) }.getOrNull()
                                ?: return@withContext CoverTransferResult.TEMPORARY_ERROR
                            continue
                        }
                        if (status == 429 || status == 503) {
                            val delayMillis = retryAfterDelayMillis(
                                connection.getHeaderField("Retry-After")
                            ) ?: 2_000L
                            retryAfterUntil.updateAndGet { previous ->
                                maxOf(previous, System.currentTimeMillis() + delayMillis)
                            }
                        }
                        coverTransferFailureForHttpStatus(status)?.let { return@withContext it }
                        val contentType = connection.contentType.orEmpty().substringBefore(';').trim()
                        if (!contentType.startsWith("image/", ignoreCase = true) ||
                            connection.contentLengthLong > CoverImageFiles.MAX_BYTES
                        ) return@withContext CoverTransferResult.TEMPORARY_ERROR

                        connection.inputStream.use { input ->
                            destination.outputStream().buffered().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var total = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    total += read
                                    if (total > CoverImageFiles.MAX_BYTES) {
                                        return@withContext CoverTransferResult.TEMPORARY_ERROR
                                    }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        return@withContext CoverTransferResult.DOWNLOADED
                    } finally {
                        connection.disconnect()
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                CoverTransferResult.TEMPORARY_ERROR
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                if (failure is UnknownHostException || failure is ConnectException ||
                    failure is SocketException || failure is SocketTimeoutException
                ) CoverTransferResult.NETWORK_ERROR
                else CoverTransferResult.TEMPORARY_ERROR
            }
        }

    private companion object {
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val retryAfterUntil = AtomicLong(0L)
    }
}
