package org.rankoffline.app

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
                    "operation=resolve-anime-cover provider=$provider id=${item.id} failed=${exception.message}"
                )
            } catch (exception: Exception) {
                lastFailure = IOException("Unable to resolve Anime cover metadata", exception)
            }
        }
        lastFailure?.let { throw it }
        emptyList()
    }

    private fun resolveAniList(id: Long): String? {
        val body = JSONObject()
            .put("query", "query (\$id: Int) { Media(id: \$id, type: ANIME) { coverImage { extraLarge large medium } } }")
            .put("variables", JSONObject().put("id", id))
        val connection = open("https://graphql.anilist.co").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        return connection.useJson(body)?.optJSONObject("data")?.optJSONObject("Media")
            ?.optJSONObject("coverImage")?.let { cover ->
                sequenceOf("extraLarge", "large", "medium")
                    .mapNotNull { cover.optString(it).takeIf(String::isNotBlank) }
                    .firstOrNull()
            }
    }

    private fun resolveJikan(id: Long): String? {
        val connection = open("https://api.jikan.moe/v4/anime/$id")
        return connection.useJson()?.optJSONObject("data")?.optJSONObject("images")
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

    private fun HttpURLConnection.useJson(body: JSONObject? = null): JSONObject? = try {
        body?.let { outputStream.bufferedWriter().use { writer -> writer.write(it.toString()) } }
        when (responseCode) {
            HttpURLConnection.HTTP_NOT_FOUND -> null
            in 200..299 -> inputStream.bufferedReader().use { JSONObject(it.readText()) }
            else -> throw IOException("HTTP $responseCode")
        }
    } finally {
        disconnect()
    }

    private companion object { const val TAG = "RankOfflineCover" }
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
