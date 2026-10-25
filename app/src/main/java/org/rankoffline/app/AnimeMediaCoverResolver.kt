package org.rankoffline.app

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Provider adapter used only when the normalized Anime pack has no direct image reference. */
fun interface AnimeMediaCoverResolver {
    suspend fun resolve(item: MediaItem): List<URI>
}

class DefaultAnimeMediaCoverResolver : AnimeMediaCoverResolver {
    override suspend fun resolve(item: MediaItem): List<URI> = withContext(Dispatchers.IO) {
        val identifiers = animeCoverIdentifiers(item)
        var lastFailure: IOException? = null
        identifiers.forEach { (provider, id) ->
            try {
                val url = when (provider) {
                    "anilist" -> resolveAniList(id)
                    "mal" -> resolveJikan(id)
                    else -> null
                }
                if (!url.isNullOrBlank()) return@withContext listOf(URI.create(url))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: IOException) {
                lastFailure = exception
                Log.w(
                    TAG,
                    "operation=resolve-anime-cover provider=$provider item=${item.id} stage=metadata " +
                        "classification=retryable failed=${exception.javaClass.simpleName}: ${exception.message}"
                )
            } catch (exception: Exception) {
                lastFailure = IOException("Unable to resolve Anime cover metadata", exception)
            }
        }
        lastFailure?.let { throw it }
        emptyList()
    }

    private suspend fun resolveAniList(id: Long): String? {
        val body = JSONObject()
            .put("query", "query (\$id: Int) { Media(id: \$id, type: ANIME) { coverImage { extraLarge large medium } } }")
            .put("variables", JSONObject().put("id", id))
        val connection = open("https://graphql.anilist.co").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        return connection.useJson("anilist", id, body)?.optJSONObject("data")?.optJSONObject("Media")
            ?.optJSONObject("coverImage")?.let { cover ->
                sequenceOf("extraLarge", "large", "medium")
                    .mapNotNull { cover.optString(it).takeIf(String::isNotBlank) }
                    .firstOrNull()
            }
    }

    private suspend fun resolveJikan(id: Long): String? {
        val connection = open("https://api.jikan.moe/v4/anime/$id")
        return connection.useJson("jikan", id)?.optJSONObject("data")?.optJSONObject("images")
            ?.optJSONObject("jpg")?.let { images ->
                images.optString("large_image_url").takeIf(String::isNotBlank)
                    ?: images.optString("image_url").takeIf(String::isNotBlank)
            }
    }

    private fun open(value: String): HttpURLConnection =
        (URL(value).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RankOffline/0.2")
        }

    private suspend fun HttpURLConnection.useJson(
        provider: String,
        providerId: Long,
        body: JSONObject? = null
    ): JSONObject? = try {
        awaitProviderCooldown(url.host)
        val startedAt = System.nanoTime()
        body?.let { outputStream.bufferedWriter().use { writer -> writer.write(it.toString()) } }
        val status = responseCode
        when (status) {
            HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_GONE -> {
                logHttpResult(provider, providerId, status, "permanent", startedAt)
                null
            }
            in 200..299 -> inputStream.bufferedReader().use { JSONObject(it.readText()) }
            else -> {
                val classification = animeMetadataHttpFailure(status)
                if (status == HTTP_TOO_MANY_REQUESTS || status == HttpURLConnection.HTTP_UNAVAILABLE) {
                    registerProviderCooldown(url.host, retryAfterMillis())
                }
                logHttpResult(provider, providerId, status, classification.name.lowercase(), startedAt)
                throw IOException("HTTP $status (${classification.name.lowercase()})")
            }
        }
    } finally {
        disconnect()
    }

    private fun HttpURLConnection.retryAfterMillis(): Long {
        val seconds = getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: DEFAULT_COOLDOWN_SECONDS
        return seconds.coerceIn(0L, MAX_COOLDOWN_SECONDS) * 1_000L
    }

    private suspend fun awaitProviderCooldown(host: String) {
        val remaining = (providerCooldownUntil[host] ?: 0L) - System.currentTimeMillis()
        if (remaining > 0L) delay(remaining.coerceAtMost(MAX_COOLDOWN_SECONDS * 1_000L))
    }

    private fun registerProviderCooldown(host: String, delayMillis: Long) {
        val until = System.currentTimeMillis() + delayMillis
        providerCooldownUntil.compute(host) { _, current -> maxOf(current ?: 0L, until) }
    }

    private fun logHttpResult(
        provider: String,
        providerId: Long,
        status: Int,
        classification: String,
        startedAt: Long
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG,
            "operation=resolve-anime-cover provider=$provider providerId=$providerId stage=metadata " +
                "httpClass=${status / 100}xx status=$status classification=$classification " +
                "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000L}"
        )
    }

    private companion object {
        const val TAG = "RankOfflineCover"
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val DEFAULT_COOLDOWN_SECONDS = 1L
        const val MAX_COOLDOWN_SECONDS = 10L
        val providerCooldownUntil = ConcurrentHashMap<String, Long>()
    }
}

internal enum class AnimeMetadataHttpFailure { PERMANENT, RETRYABLE }

internal fun animeMetadataHttpFailure(status: Int): AnimeMetadataHttpFailure = when (status) {
    HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_GONE ->
        AnimeMetadataHttpFailure.PERMANENT
    else -> AnimeMetadataHttpFailure.RETRYABLE
}

private fun animeCoverIdentifiers(item: MediaItem): List<Pair<String, Long>> = buildList {
    if (item.remoteCoverRef?.startsWith("anime:v1|") == true) {
        item.remoteCoverRef.substringAfter('|').split('|').forEach { encoded ->
            val provider = encoded.substringBefore('=', "")
            val id = encoded.substringAfter('=', "").toLongOrNull()
            if (provider in setOf("anilist", "mal") && id != null && id > 0L) add(provider to id)
        }
    }
    val canonicalProvider = item.id.substringBefore(':')
    val canonicalId = item.id.substringAfter(':', "").toLongOrNull()
    if (canonicalProvider in setOf("anilist", "mal") && canonicalId != null && canonicalId > 0L) {
        add(canonicalProvider to canonicalId)
    }
}.distinct()
