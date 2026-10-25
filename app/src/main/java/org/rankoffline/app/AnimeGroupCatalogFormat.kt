package org.rankoffline.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

const val ANIME_GROUP_CATALOG_FORMAT_VERSION = 1

@Serializable
data class AnimeGroupCatalogManifest(
    val formatVersion: Int,
    val provider: String,
    val catalogVersion: String,
    val sha256: String,
    val groupCount: Long,
    val membershipCount: Long
)

@Serializable
data class AnimeGroupCatalogMember(val animeId: String, val position: Int)

@Serializable
data class AnimeGroupCatalogRecord(
    val id: String,
    val type: String,
    val rootAnimeId: String,
    val displayTitle: String,
    val members: List<AnimeGroupCatalogMember>
)

private val animeGroupJson = Json { ignoreUnknownKeys = true }

fun parseAnimeGroupCatalogManifest(value: String): AnimeGroupCatalogManifest =
    animeGroupJson.decodeFromString(value)

fun parseAnimeGroupCatalogRecord(value: String): AnimeGroupCatalogRecord =
    animeGroupJson.decodeFromString(value)

internal fun validateAnimeGroupCatalogManifest(manifest: AnimeGroupCatalogManifest) {
    require(manifest.formatVersion == ANIME_GROUP_CATALOG_FORMAT_VERSION)
    require(manifest.provider.isNotBlank() && manifest.catalogVersion.isNotBlank())
    require(manifest.sha256.matches(Regex("[0-9a-f]{64}")))
    require(manifest.groupCount > 0 && manifest.membershipCount >= manifest.groupCount * 2)
}

internal fun validateAnimeGroupCatalogRecord(record: AnimeGroupCatalogRecord) {
    require(record.id.isNotBlank() && record.type.isNotBlank())
    require(record.rootAnimeId.isNotBlank() && record.displayTitle.isNotBlank())
    require(record.members.size >= 2)
    require(record.members.map { it.animeId }.distinct().size == record.members.size)
    require(record.members.map { it.position } == record.members.indices.toList())
    require(record.members.any { it.animeId == record.rootAnimeId })
}
