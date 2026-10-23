package org.rankoffline.app

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RemoteSearchPage(
    val records: List<MediaCatalogRecord>,
    val nextOffset: Int,
    val hasMore: Boolean
)

interface RemoteSearchProvider {
    val mediaType: MediaType
    suspend fun search(query: String, offset: Int): RemoteSearchPage
}

class RemoteSearchException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal object RemoteSearchProviders {
    fun supports(mediaType: MediaType): Boolean =
        mediaType == MediaType.MANGA || mediaType == MediaType.BOOK

    fun forMediaType(mediaType: MediaType): RemoteSearchProvider? = when (mediaType) {
        MediaType.MANGA -> MangaDexRemoteSearchProvider()
        MediaType.BOOK -> OpenLibraryRemoteSearchProvider()
        MediaType.ANIME, MediaType.MOVIE, MediaType.SERIES -> null
    }
}

internal class MangaDexRemoteSearchProvider(
    private val client: JsonSearchClient = FixedHostJsonClient("api.mangadex.org")
) : RemoteSearchProvider {
    override val mediaType = MediaType.MANGA

    override suspend fun search(query: String, offset: Int): RemoteSearchPage {
        require(query.isNotBlank())
        val limit = 25
        val root = client.get(
            "/manga",
            listOf(
                "title" to query.trim(),
                "limit" to limit.toString(),
                "offset" to offset.toString(),
                "order[relevance]" to "desc",
                "contentRating[]" to "safe",
                "contentRating[]" to "suggestive",
                "contentRating[]" to "erotica",
                "includes[]" to "cover_art",
                "includes[]" to "author",
                "includes[]" to "artist"
            )
        )
        val data = root.optJSONArray("data") ?: JSONArray()
        val records = buildList {
            for (index in 0 until data.length()) {
                val resource = data.optJSONObject(index) ?: continue
                mapMangaDexRecord(resource)?.let(::add)
            }
        }
        val total = root.optInt("total", offset + data.length())
        return RemoteSearchPage(records, offset + data.length(), offset + data.length() < total)
    }
}

internal class OpenLibraryRemoteSearchProvider(
    private val client: JsonSearchClient = FixedHostJsonClient("openlibrary.org")
) : RemoteSearchProvider {
    override val mediaType = MediaType.BOOK

    override suspend fun search(query: String, offset: Int): RemoteSearchPage {
        require(query.isNotBlank())
        val limit = 25
        val root = client.get(
            "/search.json",
            listOf(
                "q" to query.trim(),
                "fields" to "key,title,alternative_title,first_publish_year,author_name,subject,cover_i,isbn,edition_key,language",
                "limit" to limit.toString(),
                "offset" to offset.toString()
            )
        )
        val docs = root.optJSONArray("docs") ?: JSONArray()
        val records = buildList {
            for (index in 0 until docs.length()) {
                val document = docs.optJSONObject(index) ?: continue
                mapOpenLibraryRecord(document)?.let(::add)
            }
        }
        val total = root.optInt("numFound", offset + docs.length())
        return RemoteSearchPage(records, offset + docs.length(), offset + docs.length() < total)
    }
}

internal fun interface JsonSearchClient {
    suspend fun get(path: String, parameters: List<Pair<String, String>>): JSONObject
}

internal class FixedHostJsonClient(
    private val host: String,
    private val maxResponseBytes: Int = 8 * 1024 * 1024
) : JsonSearchClient {
    override suspend fun get(path: String, parameters: List<Pair<String, String>>): JSONObject =
        withContext(Dispatchers.IO) {
            require(path.startsWith('/'))
            val query = parameters.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
            val uri = URI("https", host, path, query, null)
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "RankOffline/0.1.3")
                val code = connection.responseCode
                if (code !in 200..299) throw RemoteSearchException("Remote search failed with HTTP $code")
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxResponseBytes) throw RemoteSearchException("Remote search response is too large")
                        output.write(buffer, 0, read)
                    }
                }
                JSONObject(output.toString(StandardCharsets.UTF_8.name()))
            } catch (exception: RemoteSearchException) {
                throw exception
            } catch (exception: Exception) {
                throw RemoteSearchException("Remote search request failed", exception)
            } finally {
                connection.disconnect()
            }
        }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

internal fun mapMangaDexRecord(resource: JSONObject): MediaCatalogRecord? {
    val providerId = resource.optString("id").takeIf(String::isNotBlank) ?: return null
    val attributes = resource.optJSONObject("attributes") ?: return null
    val titles = attributes.optJSONObject("title").localizedValues()
    val originalLanguage = attributes.optString("originalLanguage").takeIf(String::isNotBlank)
    val title = titles["en"]
        ?: titles.entries.firstOrNull { it.key.endsWith("-ro") }?.value
        ?: originalLanguage?.let(titles::get)
        ?: titles.toSortedMap().values.firstOrNull()
        ?: return null
    val originalTitle = originalLanguage?.let(titles::get)
        ?.takeUnless { it == title }
    val aliases = linkedMapOf<String, MediaCatalogAlias>()
    attributes.optJSONArray("altTitles").objects().forEach { alternative ->
        alternative.localizedValues().forEach { (language, value) ->
            if (!value.equals(title, ignoreCase = true)) {
                aliases.putIfAbsent(value.lowercase(), MediaCatalogAlias(value, language))
            }
        }
    }
    var cover: String? = null
    val credits = linkedSetOf<MediaCatalogCredit>()
    resource.optJSONArray("relationships").objects().forEach { relationship ->
        val type = relationship.optString("type")
        val name = relationship.optJSONObject("attributes")?.optString("name")
            ?.takeIf(String::isNotBlank)
        when (type) {
            "cover_art" -> cover = relationship.optJSONObject("attributes")
                ?.optString("fileName")?.takeIf(String::isNotBlank)
            "author" -> name?.let { credits += MediaCatalogCredit("AUTHOR", it) }
            "artist" -> name?.let { credits += MediaCatalogCredit("ARTIST", it) }
        }
    }
    val genres = attributes.optJSONArray("tags").objects().mapNotNull { tag ->
        val names = tag.optJSONObject("attributes")?.optJSONObject("name").localizedValues()
        names["en"] ?: names.toSortedMap().values.firstOrNull()
    }.distinct().toList()
    return MediaCatalogRecord(
        id = "manga:mangadex:$providerId",
        mediaType = MediaType.MANGA.persistedValue,
        provider = "mangadex",
        providerId = providerId,
        title = title,
        originalTitle = originalTitle,
        year = attributes.optIntOrNull("year"),
        format = "MANGA",
        status = attributes.optString("status").takeIf(String::isNotBlank),
        originalLanguage = originalLanguage,
        demographic = attributes.optString("publicationDemographic").takeIf(String::isNotBlank),
        remoteCoverRef = cover,
        genres = genres,
        credits = credits.toList(),
        aliases = aliases.values.take(30)
    )
}

internal fun mapOpenLibraryRecord(document: JSONObject): MediaCatalogRecord? {
    val workId = document.optString("key").removePrefix("/works/")
        .takeIf { it.matches(Regex("OL[0-9]+W")) } ?: return null
    val title = document.optString("title").takeIf(String::isNotBlank) ?: return null
    val aliases = document.optJSONArray("alternative_title").strings()
        .filterNot { it.equals(title, ignoreCase = true) }
        .distinctBy(String::lowercase)
        .take(30)
        .map { MediaCatalogAlias(it) }
    val authors = document.optJSONArray("author_name").strings().distinct()
        .map { MediaCatalogCredit("AUTHOR", it) }
    val subjects = document.optJSONArray("subject").strings().distinct().take(50)
    val normalizedIsbns = document.optJSONArray("isbn").strings().map(::normalizeOpenLibraryIsbn)
    val isbn13 = normalizedIsbns.firstOrNull { it.matches(Regex("97[89][0-9]{10}")) }
    val isbn10 = normalizedIsbns.firstOrNull { it.matches(Regex("[0-9]{9}[0-9X]")) }
    val editionOlid = document.optJSONArray("edition_key").strings()
        .map(String::uppercase)
        .firstOrNull { it.matches(Regex("OL[0-9]+M")) }
    return MediaCatalogRecord(
        id = "book:openlibrary:$workId",
        mediaType = MediaType.BOOK.persistedValue,
        provider = "openlibrary",
        providerId = workId,
        title = title,
        year = document.optIntOrNull("first_publish_year"),
        format = "BOOK",
        originalLanguage = document.optJSONArray("language").strings().firstOrNull(),
        remoteCoverRef = encodeOpenLibraryCoverReference(
            coverId = document.optIntOrNull("cover_i")?.toString(),
            isbn13 = isbn13,
            isbn10 = isbn10,
            editionOlid = editionOlid,
            workOlid = workId
        ),
        genres = subjects,
        credits = authors,
        aliases = aliases
    )
}

private fun normalizeOpenLibraryIsbn(value: String): String =
    value.uppercase().filterNot { it == '-' || it.isWhitespace() }

private fun JSONObject?.localizedValues(): Map<String, String> {
    if (this == null) return emptyMap()
    return keys().asSequence().mapNotNull { key ->
        optString(key).trim().takeIf(String::isNotBlank)?.let { key to it }
    }.toMap()
}

private fun JSONArray?.objects(): Sequence<JSONObject> = sequence {
    if (this@objects == null) return@sequence
    for (index in 0 until length()) optJSONObject(index)?.let { yield(it) }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
    }
}

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key).takeIf { it > 0 } else null
