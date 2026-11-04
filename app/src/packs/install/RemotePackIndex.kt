package org.rankoffline.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Generic remote source. It only obtains metadata; PackManager remains the sole installer. */
internal class HttpsPackIndexSource(indexUrl: URI, allowedHosts: Set<String>) {
    private val url = indexUrl
    private val trustedHosts = TrustedHttpsHosts(allowedHosts)

    init { require(trustedHosts.approves(url)) { "Pack index URL is not trusted HTTPS" } }

    suspend fun load(): List<PackDownloadDescriptor> = withContext(Dispatchers.IO) {
        var current = url
        var redirects = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!trustedHosts.approves(current)) throw IOException("Untrusted pack index redirect")
                val connection = (current.toURL().openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "RankOffline")
                }
                try {
                    when (val code = connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            val declared = connection.contentLengthLong
                            if (declared > MAX_INDEX_BYTES) throw IOException("Pack index is too large")
                            val bytes = connection.inputStream.use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var size = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    size += count
                                    if (size > MAX_INDEX_BYTES) throw IOException("Pack index is too large")
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                            return@withContext PackIndexParser.parse(decodeUtf8(bytes), trustedHosts)
                        }
                        in REDIRECT_CODES -> {
                            if (redirects++ >= MAX_REDIRECTS) throw IOException("Too many pack index redirects")
                            val location = connection.getHeaderField("Location")
                                ?: throw IOException("Pack index redirect has no location")
                            current = current.resolve(location)
                        }
                        404, 410 -> throw IOException("Pack index is not published (HTTP $code)")
                        else -> throw IOException("Pack index request failed (HTTP $code)")
                    }
                } finally {
                    connection.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            emptyList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private companion object {
        const val MAX_INDEX_BYTES = 2L * 1024L * 1024L
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/** Strict schema-1 distribution-index parser backed by the real schema-2 manifest parser. */
internal object PackIndexParser {
    private const val MAX_PACKS = 128
    private val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,126}\\.pack")

    fun parse(encoded: String, trustedHosts: TrustedHttpsHosts): List<PackDownloadDescriptor> {
        val root = JSONObject(encoded)
        root.requireOnlyKeys("schemaVersion", "generatedBy", "packs")
        require(root.strictLong("schemaVersion") == 1L) { "Unsupported pack index schema" }
        root.optionalText("generatedBy", 160)
        val entries = root.getJSONArray("packs")
        require(entries.length() in 1..MAX_PACKS) { "Invalid number of pack descriptors" }
        val descriptors = entries.objects().map { parseEntry(it, trustedHosts) }
        require(descriptors.map { it.packId }.distinct().size == descriptors.size) {
            "Duplicate pack identity in index"
        }
        require(descriptors.map { it.registryId }.distinct().size == descriptors.size) {
            "Duplicate registry identity in index"
        }
        return descriptors
    }

    private fun parseEntry(entry: JSONObject, trustedHosts: TrustedHttpsHosts): PackDownloadDescriptor {
        entry.requireOnlyKeys("packId", "version", "displayName", "fileName", "archiveBytes",
            "archiveSha256", "registryId", "archiveUrl", "manifest")
        val manifest = PackManifestParser.parse(entry.getJSONObject("manifest"))
        require(PackId.fromJson(entry.get("packId")) == manifest.packId)
        require(entry.requiredText("version", 80) == manifest.version)
        require(entry.requiredText("displayName", 160) == manifest.displayName)
        val fileName = entry.requiredText("fileName", 128)
        require(fileName.matches(FILE_NAME) && ".." !in fileName) { "Invalid pack archive name" }
        val archiveUrl = URI(entry.requiredText("archiveUrl", 2_048))
        require(trustedHosts.approves(archiveUrl)) { "Untrusted pack archive URL" }
        require(archiveUrl.path?.substringAfterLast('/') == fileName) {
            "Pack archive URL and file name differ"
        }
        return PackDownloadDescriptor(
            manifest = manifest,
            archiveUrl = archiveUrl,
            archiveBytes = entry.strictLong("archiveBytes"),
            archiveSha256 = entry.requiredText("archiveSha256", 64),
            registryId = entry.requiredText("registryId", 128)
        )
    }

    private fun JSONObject.strictLong(key: String): Long {
        val number = get(key)
        require(number is Number) { "$key must be a JSON integer" }
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) {
            "$key must be a JSON integer"
        }
        return value
    }
}
