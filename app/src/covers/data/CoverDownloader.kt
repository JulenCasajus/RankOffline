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
import java.util.concurrent.ConcurrentHashMap
import android.util.Log
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
                    val retryGate = retryAfterByHost.computeIfAbsent(host) { AtomicLong(0L) }
                    val wait = retryGate.get() - System.currentTimeMillis()
                    if (wait > 0L) delay(wait)
                    if (!isSafeHttpsUri(current, setOf(host))) {
                        CoverFailureDiagnostics.record(host, "unsafe-redirect")
                        return@withContext CoverTransferResult.TEMPORARY_ERROR
                    }
                    if (!resolvesToPublicAddresses(host)) {
                        CoverFailureDiagnostics.record(host, "non-public-dns")
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
                            if (redirects++ >= 3) {
                                CoverFailureDiagnostics.record(host, "redirect-limit")
                                return@withContext CoverTransferResult.TEMPORARY_ERROR
                            }
                            val location = connection.getHeaderField("Location")
                                ?: return@withContext CoverTransferResult.TEMPORARY_ERROR
                            current = runCatching { current.resolve(location) }.getOrNull()
                                ?: return@withContext CoverTransferResult.TEMPORARY_ERROR
                            continue
                        }
                        if (status !in 200..299) {
                            CoverFailureDiagnostics.record(host, "http-$status")
                        }
                        if (status == 429 || status == 503) {
                            val delayMillis = retryAfterDelayMillis(
                                connection.getHeaderField("Retry-After")
                            ) ?: 2_000L
                            retryGate.updateAndGet { previous ->
                                maxOf(previous, System.currentTimeMillis() + delayMillis)
                            }
                        }
                        coverTransferFailureForHttpStatus(status)?.let { return@withContext it }
                        val contentType = connection.contentType.orEmpty().substringBefore(';').trim()
                        if (!contentType.startsWith("image/", ignoreCase = true) ||
                            connection.contentLengthLong > CoverImageFiles.MAX_BYTES
                        ) {
                            CoverFailureDiagnostics.record(host, "invalid-response")
                            return@withContext CoverTransferResult.TEMPORARY_ERROR
                        }

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
                                        CoverFailureDiagnostics.record(host, "oversized-image")
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
                CoverFailureDiagnostics.record(host, failure.javaClass.simpleName)
                if (failure is UnknownHostException || failure is ConnectException ||
                    failure is SocketException || failure is SocketTimeoutException
                ) CoverTransferResult.NETWORK_ERROR
                else CoverTransferResult.TEMPORARY_ERROR
            }
        }

    private companion object {
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val retryAfterByHost = ConcurrentHashMap<String, AtomicLong>()
    }
}

/** Aggregates failures without logging cover identities or flooding Logcat. */
internal object CoverFailureDiagnostics {
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    fun record(host: String?, cause: String) {
        val safeHost = host?.lowercase()?.take(120) ?: "unknown-host"
        val key = "$safeHost:$cause"
        val count = counts.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        if (count == 1L || count % 25L == 0L) {
            Log.d("RankOfflineCover", "source=$safeHost failure=$cause count=$count")
        }
    }
}
