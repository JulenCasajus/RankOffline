package org.rankoffline.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class CoverMetadataResolution(
    val candidates: List<URI> = emptyList(),
    val failure: CoverDownloadResult? = null,
    val allowedHosts: Set<String> = emptySet()
)

internal data class CoverMetadataCandidate(val source: PackCoverSource, val identity: String)

internal fun parseMetadataReference(reference: String?): Map<String, String>? {
    if (reference?.startsWith("cover:v1|") != true || reference.length > 2_048) return null
    val tokens = reference.removePrefix("cover:v1|").split('|')
    if (tokens.isEmpty()) return null
    val values = linkedMapOf<String, String>()
    for (token in tokens) {
        val key = token.substringBefore('=')
        val value = token.substringAfter('=', "")
        if (!key.matches(METADATA_REFERENCE_KEY) ||
            value.toLongOrNull()?.let { it > 0L && it.toString() == value } != true ||
            values.put(key, value) != null) return null
    }
    return values
}

private val METADATA_REFERENCE_KEY = Regex("[a-z][a-z0-9_]{0,63}")

/** Executes only the bounded HTTPS/JSON recipe already validated from the installed pack. */
internal object DeclarativeCoverMetadataResolver {
    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private val requestGates = ConcurrentHashMap<String, MetadataRequestGate>()

    suspend fun resolve(candidate: CoverMetadataCandidate): CoverMetadataResolution =
        withContext(Dispatchers.IO) {
            val request = candidate.source.metadataRequest
                ?: return@withContext CoverMetadataResolution()
            try {
                val json = request(candidate.source, request, candidate.identity)
                    ?: return@withContext CoverMetadataResolution()
                if ((json.optJSONArray("errors")?.length() ?: 0) > 0) {
                    return@withContext CoverMetadataResolution(
                        failure = CoverDownloadResult.TemporaryError
                    )
                }
                imageResolution(json, request.imageJsonPaths, candidate.source.allowedHosts)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MetadataRequestException) {
                CoverMetadataResolution(failure = failure.result)
            } catch (failure: IOException) {
                val result = if (failure is UnknownHostException || failure is ConnectException ||
                    failure is SocketException || failure is SocketTimeoutException
                ) CoverDownloadResult.NetworkError else CoverDownloadResult.TemporaryError
                CoverMetadataResolution(failure = result)
            } catch (_: Exception) {
                CoverMetadataResolution(failure = CoverDownloadResult.TemporaryError)
            }
        }

    private suspend fun request(
        source: PackCoverSource,
        config: PackCoverMetadataRequest,
        identity: String
    ): JSONObject? {
        val target = config.urlTemplate.replace("{reference}", identity)
        val body = config.bodyTemplate?.replace("{reference}", identity)
        val targetUri = URI.create(target)
        val host = targetUri.host ?: throw MetadataRequestException(CoverDownloadResult.TemporaryError)
        val gateKey = "${source.id}|${config.method}|${host.lowercase()}|${config.urlTemplate}"
        val gate = requestGates.computeIfAbsent(gateKey) { MetadataRequestGate() }
        if (!isSafeHttpsUri(targetUri, setOf(host.lowercase(java.util.Locale.ROOT))) ||
            !resolvesToPublicAddresses(host)) {
            throw MetadataRequestException(CoverDownloadResult.TemporaryError)
        }
        gate.lock.withLock {
            val wait = gate.nextRequestAt - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            gate.nextRequestAt = System.currentTimeMillis() + config.requestIntervalMillis
        }
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = if (config.method == "GET") "GET" else "POST"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RankOffline/0.2")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            body?.let { value ->
                connection.outputStream.bufferedWriter().use { it.write(value) }
            }
            val status = connection.responseCode
            if (status == 403 || status == 404 || status == 410) return null
            if (status == 429 || status == 503) {
                val retryDelay = retryAfterDelayMillis(connection.getHeaderField("Retry-After"))
                    ?: 2_000L
                gate.lock.withLock {
                    gate.nextRequestAt = maxOf(gate.nextRequestAt,
                        System.currentTimeMillis() + retryDelay)
                }
            }
            if (status !in 200..299) {
                val result = when (coverTransferFailureForHttpStatus(status)) {
                    CoverTransferResult.NETWORK_ERROR -> CoverDownloadResult.NetworkError
                    CoverTransferResult.TEMPORARY_ERROR -> CoverDownloadResult.TemporaryError
                    else -> CoverDownloadResult.TemporaryError
                }
                throw MetadataRequestException(result)
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(4 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw IOException("Oversized cover metadata")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return JSONObject(output.toString(Charsets.UTF_8.name()))
        } finally {
            connection.disconnect()
        }
    }

    private fun imageResolution(
        root: JSONObject,
        paths: List<String>,
        hosts: Set<String>
    ): CoverMetadataResolution {
        var invalidCandidate = false
        val candidates = paths.mapNotNull { path ->
            val value = path.split('.').fold(root as Any?) { current, key ->
                (current as? JSONObject)?.opt(key)
            } as? String
            val raw = value?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val uri = runCatching { URI.create(raw) }.getOrNull()
            if (uri == null || !isSafeHttpsUri(uri, hosts)) invalidCandidate = true
            uri?.takeIf { isSafeHttpsUri(it, hosts) }
        }.distinct()
        return CoverMetadataResolution(
            candidates = candidates,
            failure = CoverDownloadResult.TemporaryError.takeIf { invalidCandidate },
            allowedHosts = hosts
        )
    }
}

private class MetadataRequestGate(
    val lock: Mutex = Mutex(),
    var nextRequestAt: Long = 0L
)

private class MetadataRequestException(val result: CoverDownloadResult) : IOException()
