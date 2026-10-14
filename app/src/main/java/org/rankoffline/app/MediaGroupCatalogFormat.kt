package org.rankoffline.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val MEDIA_GROUP_CATALOG_FORMAT_VERSION = 1

@Serializable
data class MediaGroupCatalogManifest(
    val formatVersion: Int,
    val mediaType: String,
    val provider: String,
    val catalogVersion: String,
    val sha256: String,
    val groupCount: Long
)

@Serializable
data class MediaGroupCatalogMember(
    val mediaId: String,
    val position: Int
)

@Serializable
data class MediaGroupCatalogRecord(
    val id: String,
    val mediaType: String,
    val groupType: String,
    val provider: String,
    val providerGroupId: String,
    val displayTitle: String,
    val remoteCoverRef: String? = null,
    val members: List<MediaGroupCatalogMember>
)

private val groupCatalogJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun parseMediaGroupCatalogManifest(value: String): MediaGroupCatalogManifest =
    groupCatalogJson.decodeFromString(value)

fun parseMediaGroupCatalogRecord(value: String): MediaGroupCatalogRecord =
    groupCatalogJson.decodeFromString(value)

internal fun encodeMediaGroupCatalogRecord(record: MediaGroupCatalogRecord): String =
    groupCatalogJson.encodeToString(record)

internal fun validateMediaGroupCatalogManifest(manifest: MediaGroupCatalogManifest): MediaType {
    require(manifest.formatVersion == MEDIA_GROUP_CATALOG_FORMAT_VERSION)
    val mediaType = MediaType.entries.firstOrNull { it.persistedValue == manifest.mediaType }
    require(mediaType != null && mediaType != MediaType.ANIME)
    require(manifest.provider.isNotBlank())
    require(manifest.catalogVersion.isNotBlank())
    require(manifest.sha256.matches(Regex("[0-9a-f]{64}")))
    require(manifest.groupCount >= 0)
    return mediaType
}

internal fun validateMediaGroupCatalogRecord(
    record: MediaGroupCatalogRecord,
    manifest: MediaGroupCatalogManifest
) {
    require(record.id.isNotBlank() && record.id.startsWith("${manifest.mediaType}:"))
    require(record.mediaType == manifest.mediaType)
    require(record.provider == manifest.provider)
    require(record.groupType.isNotBlank())
    require(record.providerGroupId.isNotBlank())
    require(record.displayTitle.isNotBlank())
    require(record.members.size >= 2) { "A media group requires at least two members" }
    require(record.members.all { it.mediaId.isNotBlank() })
    require(record.members.map { it.mediaId }.distinct().size == record.members.size) {
        "A media group contains duplicate members"
    }
}
