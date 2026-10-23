package org.rankoffline.app

import java.net.URI
import java.security.MessageDigest

fun interface MediaCoverProvider {
    fun resolve(item: MediaItem): URI?

    /** Ordered alternatives for providers that expose more than one stable cover identifier. */
    fun resolveCandidates(item: MediaItem): List<URI> = listOfNotNull(resolve(item))
}

class DefaultMediaCoverProvider : MediaCoverProvider {
    override fun resolve(item: MediaItem): URI? = resolveCandidates(item).firstOrNull()

    override fun resolveCandidates(item: MediaItem): List<URI> = when (item.provider) {
        OPEN_LIBRARY_PROVIDER -> resolveOpenLibrary(item.remoteCoverRef, item.providerId)
        else -> listOfNotNull(resolveSingle(item))
    }

    private fun resolveSingle(item: MediaItem): URI? = when (item.provider) {
        TMDB_PROVIDER -> resolveTmdb(item.remoteCoverRef)
        MANGADEX_PROVIDER -> resolveMangaDex(item.providerId, item.remoteCoverRef)
        else -> null
    }

    private fun resolveTmdb(reference: String?): URI? {
        val filename = safeFilename(reference?.removePrefix("/")) ?: return null
        return httpsUri(TMDB_IMAGE_HOST, "/t/p/w500/$filename")
    }

    private fun resolveMangaDex(providerId: String?, reference: String?): URI? {
        val mangaId = providerId?.takeIf { MANGADEX_UUID.matches(it) } ?: return null
        val filename = safeFilename(reference) ?: return null
        return httpsUri(MANGADEX_IMAGE_HOST, "/covers/$mangaId/$filename.512.jpg")
    }

    private fun resolveOpenLibrary(reference: String?, providerId: String?): List<URI> =
        decodeOpenLibraryCoverReference(reference, providerId).map { candidate ->
            URI(
                "https",
                OPEN_LIBRARY_IMAGE_HOST,
                "/b/${candidate.pathType}/${candidate.value}-L.jpg",
                "default=false",
                null
            )
        }

    private fun safeFilename(value: String?): String? = value
        ?.takeIf { it.isNotBlank() && SAFE_FILENAME.matches(it) && it != "." && it != ".." }

    private fun httpsUri(host: String, path: String) = URI("https", host, path, null)

    companion object {
        const val TMDB_PROVIDER = "tmdb"
        const val MANGADEX_PROVIDER = "mangadex"
        const val OPEN_LIBRARY_PROVIDER = "openlibrary"
        const val TMDB_IMAGE_HOST = "image.tmdb.org"
        const val MANGADEX_IMAGE_HOST = "uploads.mangadex.org"
        const val OPEN_LIBRARY_IMAGE_HOST = "covers.openlibrary.org"

        val ALLOWED_IMAGE_HOSTS = setOf(
            TMDB_IMAGE_HOST,
            MANGADEX_IMAGE_HOST,
            OPEN_LIBRARY_IMAGE_HOST,
            "s4.anilist.co",
            "cdn.myanimelist.net"
        )

        private val SAFE_FILENAME = Regex("[A-Za-z0-9._-]+")
        private val MANGADEX_UUID = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )
    }
}

private const val OPEN_LIBRARY_COVER_REFERENCE_PREFIX = "openlibrary:v1"
private val OPEN_LIBRARY_COVER_ID = Regex("[0-9]+")
private val OPEN_LIBRARY_ISBN_13 = Regex("97[89][0-9]{10}")
private val OPEN_LIBRARY_ISBN_10 = Regex("[0-9]{9}[0-9X]")
private val OPEN_LIBRARY_EDITION_OLID = Regex("OL[0-9]+M")
private val OPEN_LIBRARY_WORK_OLID = Regex("OL[0-9]+W")

private data class OpenLibraryCoverCandidate(val pathType: String, val value: String)

/**
 * Compact catalog representation. Identifiers are resolved by tooling/search, never by a cover tap.
 * A plain numeric value remains supported as the legacy cover_i representation.
 */
internal fun encodeOpenLibraryCoverReference(
    coverId: String?,
    isbn13: String?,
    isbn10: String?,
    editionOlid: String?,
    workOlid: String?
): String? {
    val entries = listOfNotNull(
        coverId?.takeIf(OPEN_LIBRARY_COVER_ID::matches)?.let { "id=$it" },
        isbn13?.normalizeIsbn()?.takeIf(OPEN_LIBRARY_ISBN_13::matches)?.let { "isbn13=$it" },
        isbn10?.normalizeIsbn()?.takeIf(OPEN_LIBRARY_ISBN_10::matches)?.let { "isbn10=$it" },
        editionOlid?.uppercase()?.takeIf(OPEN_LIBRARY_EDITION_OLID::matches)?.let { "edition=$it" },
        workOlid?.uppercase()?.takeIf(OPEN_LIBRARY_WORK_OLID::matches)?.let { "work=$it" }
    )
    return entries.takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "|", prefix = "$OPEN_LIBRARY_COVER_REFERENCE_PREFIX|")
}

private fun decodeOpenLibraryCoverReference(
    reference: String?,
    fallbackWorkOlid: String?
): List<OpenLibraryCoverCandidate> {
    val candidates = buildList {
        if (reference?.matches(OPEN_LIBRARY_COVER_ID) == true) {
            add(OpenLibraryCoverCandidate("id", reference))
        } else if (reference?.startsWith("$OPEN_LIBRARY_COVER_REFERENCE_PREFIX|") == true) {
            reference.substringAfter('|').split('|').forEach { encoded ->
                val key = encoded.substringBefore('=', missingDelimiterValue = "")
                val rawValue = encoded.substringAfter('=', missingDelimiterValue = "")
                when (key) {
                    "id" -> rawValue.takeIf(OPEN_LIBRARY_COVER_ID::matches)
                        ?.let { add(OpenLibraryCoverCandidate("id", it)) }
                    "isbn13" -> rawValue.normalizeIsbn().takeIf(OPEN_LIBRARY_ISBN_13::matches)
                        ?.let { add(OpenLibraryCoverCandidate("isbn", it)) }
                    "isbn10" -> rawValue.normalizeIsbn().takeIf(OPEN_LIBRARY_ISBN_10::matches)
                        ?.let { add(OpenLibraryCoverCandidate("isbn", it)) }
                    "edition" -> rawValue.uppercase().takeIf(OPEN_LIBRARY_EDITION_OLID::matches)
                        ?.let { add(OpenLibraryCoverCandidate("olid", it)) }
                    "work" -> rawValue.uppercase().takeIf(OPEN_LIBRARY_WORK_OLID::matches)
                        ?.let { add(OpenLibraryCoverCandidate("olid", it)) }
                }
            }
        }
        fallbackWorkOlid?.uppercase()?.takeIf(OPEN_LIBRARY_WORK_OLID::matches)
            ?.let { add(OpenLibraryCoverCandidate("olid", it)) }
    }
    return candidates.distinct()
}

private fun String.normalizeIsbn(): String =
    uppercase().filterNot { it == '-' || it.isWhitespace() }

internal fun mediaCoverFileName(mediaId: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(mediaId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + ".cover"
