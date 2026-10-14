package org.rankoffline.app

import java.net.URI
import java.security.MessageDigest

fun interface MediaCoverProvider {
    fun resolve(item: MediaItem): URI?
}

class DefaultMediaCoverProvider : MediaCoverProvider {
    override fun resolve(item: MediaItem): URI? = when (item.provider) {
        TMDB_PROVIDER -> resolveTmdb(item.remoteCoverRef)
        MANGADEX_PROVIDER -> resolveMangaDex(item.providerId, item.remoteCoverRef)
        OPEN_LIBRARY_PROVIDER -> resolveOpenLibrary(item.remoteCoverRef)
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

    private fun resolveOpenLibrary(reference: String?): URI? {
        val coverId = reference?.takeIf { COVER_ID.matches(it) } ?: return null
        return URI("https", OPEN_LIBRARY_IMAGE_HOST, "/b/id/$coverId-L.jpg", "default=false", null)
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
            OPEN_LIBRARY_IMAGE_HOST
        )

        private val SAFE_FILENAME = Regex("[A-Za-z0-9._-]+")
        private val MANGADEX_UUID = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )
        private val COVER_ID = Regex("[0-9]+")
    }
}

internal fun mediaCoverFileName(mediaId: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(mediaId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + ".cover"
