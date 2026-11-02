package org.rankoffline.app

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class CoverTransferResult { DOWNLOADED, NOT_FOUND, NETWORK_ERROR, TEMPORARY_ERROR }

internal fun coverTransferFailureForHttpStatus(status: Int): CoverTransferResult? = when {
    status in 200..299 -> null
    status == 408 || status == 425 || status == 429 || status in 500..599 ->
        CoverTransferResult.TEMPORARY_ERROR
    else -> CoverTransferResult.NOT_FOUND
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
                ?: return@withContext CoverTransferResult.NOT_FOUND
            var current = remoteUri
            var redirects = 0
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!isSafeHttpsUri(current, setOf(host))) {
                        return@withContext CoverTransferResult.NOT_FOUND
                    }
                    if (!isPublicHost(host)) return@withContext CoverTransferResult.NOT_FOUND

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
                            if (redirects++ >= 3) return@withContext CoverTransferResult.NOT_FOUND
                            val location = connection.getHeaderField("Location")
                                ?: return@withContext CoverTransferResult.NOT_FOUND
                            current = runCatching { current.resolve(location) }.getOrNull()
                                ?: return@withContext CoverTransferResult.NOT_FOUND
                            continue
                        }
                        coverTransferFailureForHttpStatus(status)?.let { return@withContext it }
                        val contentType = connection.contentType.orEmpty().substringBefore(';').trim()
                        if (!contentType.startsWith("image/", ignoreCase = true) ||
                            connection.contentLengthLong > CoverImageFiles.MAX_BYTES
                        ) return@withContext CoverTransferResult.NOT_FOUND

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
                                        return@withContext CoverTransferResult.NOT_FOUND
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

    private fun isPublicHost(host: String): Boolean =
        InetAddress.getAllByName(host).takeIf { it.isNotEmpty() }?.all { address ->
            !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                !address.isMulticastAddress && when (address) {
                    is Inet4Address -> {
                        val bytes = address.address.map { it.toInt() and 255 }
                        val a = bytes[0]
                        val b = bytes[1]
                        a in 1..223 && !(a == 100 && b in 64..127) &&
                            !(a == 192 && b == 0) && !(a == 198 && b in 18..19) &&
                            !(a == 192 && b == 0 && bytes[2] == 2) &&
                            !(a == 198 && b == 51 && bytes[2] == 100) &&
                            !(a == 203 && b == 0 && bytes[2] == 113)
                    }
                    is Inet6Address -> {
                        val bytes = address.address.map { it.toInt() and 255 }
                        // Reject ULA, documentation, unassigned and transition addresses.
                        !(bytes[0] and 0xfe == 0xfc) &&
                            !(bytes[0] == 0x20 && bytes[1] == 0x01 &&
                                bytes[2] == 0x0d && bytes[3] == 0xb8) &&
                            bytes[0] in 0x20..0x3f
                    }
                    else -> false
                }
        } == true

    private companion object {
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}
