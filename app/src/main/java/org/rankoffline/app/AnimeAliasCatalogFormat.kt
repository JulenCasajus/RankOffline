package org.rankoffline.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

const val ANIME_ALIAS_CATALOG_FORMAT_VERSION = 1
const val ANIME_ALIAS_SOURCE = "cedya77/anime-offline-database"
const val ANIME_ALIAS_ORIGINAL_PROJECT = "manami-project/anime-offline-database"
const val ANIME_ALIAS_LICENSE = "ODbL-1.0"

@Serializable
data class AnimeAliasCatalogManifest(
    val formatVersion: Int,
    val source: String,
    val sourceOriginalProject: String,
    val sourceRelease: String,
    val sourceAsset: String,
    val sourceAssetSha256: String,
    val license: String,
    val recordCount: Long,
    val aliasCount: Long,
    val sha256: String
)

@Serializable
data class AnimeAliasCatalogAlias(
    val title: String,
    val language: String? = null,
    val kind: String? = null
)

@Serializable
data class AnimeAliasCatalogRecord(
    val animeId: String,
    val aliases: List<AnimeAliasCatalogAlias>
)

private val animeAliasCatalogJson = Json { ignoreUnknownKeys = false }

fun parseAnimeAliasCatalogManifest(value: String): AnimeAliasCatalogManifest =
    animeAliasCatalogJson.decodeFromString(value)

fun parseAnimeAliasCatalogRecord(value: String): AnimeAliasCatalogRecord =
    animeAliasCatalogJson.decodeFromString(value)

internal fun validateAnimeAliasCatalogManifest(manifest: AnimeAliasCatalogManifest) {
    require(manifest.formatVersion == ANIME_ALIAS_CATALOG_FORMAT_VERSION) {
        "Unsupported Anime alias catalog format version: ${manifest.formatVersion}"
    }
    require(manifest.source == ANIME_ALIAS_SOURCE) { "Unexpected Anime alias source" }
    require(manifest.sourceOriginalProject == ANIME_ALIAS_ORIGINAL_PROJECT) {
        "Unexpected Anime alias original project"
    }
    require(manifest.sourceRelease.isNotBlank()) { "Anime alias source release must not be blank" }
    require(manifest.sourceAsset.isNotBlank()) { "Anime alias source asset must not be blank" }
    require(manifest.sourceAssetSha256.matches(Regex("[0-9a-f]{64}"))) {
        "Anime alias source SHA-256 must be lowercase hexadecimal"
    }
    require(manifest.license == ANIME_ALIAS_LICENSE) { "Unexpected Anime alias data license" }
    require(manifest.recordCount >= 0) { "Anime alias record count must not be negative" }
    require(manifest.aliasCount >= 0) { "Anime alias count must not be negative" }
    require(manifest.sha256.matches(Regex("[0-9a-f]{64}"))) {
        "Anime alias catalog SHA-256 must be lowercase hexadecimal"
    }
}
