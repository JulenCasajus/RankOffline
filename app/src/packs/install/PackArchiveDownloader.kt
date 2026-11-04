package org.rankoffline.app

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

fun interface PackArchiveDownloader {
    suspend fun download(descriptor: PackDownloadDescriptor, destination: File,
                         progress: (Long) -> Unit): PackOperationResult
}

/** HTTPS only, explicit redirect hosts; GitHub CDN hosts are configured by the caller. */
class HttpsPackArchiveDownloader(allowedHosts: Set<String>) : PackArchiveDownloader {
    private val trustedHosts = TrustedHttpsHosts(allowedHosts)

    override suspend fun download(descriptor: PackDownloadDescriptor, destination: File,
                                  progress: (Long) -> Unit): PackOperationResult = withContext(Dispatchers.IO) {
        var url = descriptor.archiveUrl
        var redirects = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!trustedHosts.approves(url)) return@withContext PackOperationResult.INVALID_PACK
                val connection = (url.toURL().openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Accept", "application/zip")
                    setRequestProperty("User-Agent", "RankOffline")
                }
                try {
                    when (val code = connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            if (connection.contentLengthLong > descriptor.archiveBytes) {
                                return@withContext PackOperationResult.INVALID_PACK
                            }
                            val hash = MessageDigest.getInstance("SHA-256")
                            var size = 0L
                            connection.inputStream.use { input ->
                                destination.outputStream().buffered().use { output ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        if (n == 0) continue
                                        if (n.toLong() > descriptor.archiveBytes - size) {
                                            return@withContext PackOperationResult.INVALID_PACK
                                        }
                                        size += n
                                        hash.update(buffer, 0, n)
                                        output.write(buffer, 0, n)
                                        progress(size)
                                    }
                                }
                            }
                            val actual = hash.digest().joinToString("") { "%02x".format(it) }
                            return@withContext if (size == descriptor.archiveBytes &&
                                actual == descriptor.archiveSha256) PackOperationResult.INSTALLED
                                else PackOperationResult.INVALID_PACK
                        }
                        in setOf(301, 302, 303, 307, 308) -> {
                            if (redirects++ >= 5) return@withContext PackOperationResult.NETWORK_ERROR
                            val location = connection.getHeaderField("Location")
                                ?: return@withContext PackOperationResult.NETWORK_ERROR
                            url = url.resolve(location)
                        }
                        404, 410 -> return@withContext PackOperationResult.NOT_FOUND
                        else -> return@withContext if (code >= 500 || code == 408 || code == 429)
                            PackOperationResult.NETWORK_ERROR else PackOperationResult.NOT_FOUND
                    }
                } finally { connection.disconnect() }
            }
            @Suppress("UNREACHABLE_CODE")
            PackOperationResult.NETWORK_ERROR
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            PackOperationResult.NETWORK_ERROR
        }
    }

}
