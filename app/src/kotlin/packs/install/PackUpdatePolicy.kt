package org.rankoffline.app

import java.math.BigInteger

/** Only the last -vN suffix is a revision; the slug may itself contain -vN. */
internal data class PackRevision(val slug: String, val number: BigInteger)

private val slugPattern = Regex("[a-z0-9][a-z0-9_-]{0,63}")
private val numberPattern = Regex("[1-9][0-9]*")

internal fun parsePackRevision(version: String): PackRevision? {
    val marker = version.lastIndexOf("-v")
    if (marker < 1 || version.length > 80) return null
    val slug = version.substring(0, marker)
    val number = version.substring(marker + 2)
    if (!slugPattern.matches(slug) || !numberPattern.matches(number)) return null
    return PackRevision(slug, BigInteger(number))
}

/** Published v0.2.2 identities, never a heuristic for arbitrary historical strings. */
private val officialLegacyVersions = mapOf(
    1L to ("anime-69005e06b4a92ee6caa4c57efcb5951086a2ffaa6b8ade997276f301908b6223-v5" to PackRevision("animes", BigInteger.valueOf(5))),
    2L to ("mangadex-2026-10-16-v3" to PackRevision("mangas", BigInteger.valueOf(3))),
    3L to ("tmdb-movies-2026-10-16-v3" to PackRevision("movies", BigInteger.valueOf(3))),
    4L to ("tmdb-series-2026-10-16-v3" to PackRevision("series", BigInteger.valueOf(3))),
    5L to ("openlibrary-2026-09-30-readinglog-top-50000-v4" to PackRevision("books", BigInteger.valueOf(4)))
)

private fun identityOf(manifest: PackManifest, officialSource: Boolean): PackRevision? {
    val explicit = manifest.slug
    if (explicit != null) return parsePackRevision(manifest.version)?.takeIf { it.slug == explicit }
    val historical = officialLegacyVersions[manifest.packId.value]
    return historical?.second?.takeIf { officialSource && historical.first == manifest.version }
}

internal fun newerPackRevision(installed: PackManifest, remote: PackDownloadDescriptor): Boolean {
    if (installed.packId != remote.packId || installed == remote.manifest) return false
    val official = remote.registryId == "rankoffline-official-${installed.packId.value}"
    val old = identityOf(installed, official) ?: return false
    val next = identityOf(remote.manifest, official) ?: return false
    return old.slug == next.slug && next.number > old.number
}
