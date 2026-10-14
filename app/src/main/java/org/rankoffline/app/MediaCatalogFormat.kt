package org.rankoffline.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val MEDIA_CATALOG_FORMAT_VERSION = 1

@Serializable
data class MediaCatalogManifest(
    val formatVersion: Int,
    val mediaType: String,
    val provider: String,
    val catalogVersion: String,
    val sha256: String,
    val itemCount: Long,
    val generatedAt: String? = null
)

@Serializable
data class MediaCatalogCredit(
    val role: String,
    val name: String
)

@Serializable
data class MediaCatalogAlias(
    val title: String,
    val language: String? = null,
    val kind: String? = null
)

@Serializable
data class MediaCatalogRecord(
    val id: String,
    val mediaType: String,
    val provider: String,
    val providerId: String,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val startDate: String? = null,
    val format: String? = null,
    val status: String? = null,
    val originalLanguage: String? = null,
    val demographic: String? = null,
    val partNumber: Int? = null,
    val popularity: Double? = null,
    val popularityRank: Int? = null,
    val remoteCoverRef: String? = null,
    val genres: List<String> = emptyList(),
    val credits: List<MediaCatalogCredit> = emptyList(),
    val aliases: List<MediaCatalogAlias> = emptyList()
)

private val catalogJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun parseMediaCatalogManifest(value: String): MediaCatalogManifest =
    catalogJson.decodeFromString(value)

fun parseMediaCatalogRecord(value: String): MediaCatalogRecord =
    catalogJson.decodeFromString(value)

internal fun encodeMediaCatalogRecord(record: MediaCatalogRecord): String =
    catalogJson.encodeToString(record)

internal fun validateMediaCatalogManifest(manifest: MediaCatalogManifest): MediaType {
    require(manifest.formatVersion == MEDIA_CATALOG_FORMAT_VERSION) {
        "Unsupported media catalog format version: ${manifest.formatVersion}"
    }
    val mediaType = MediaType.entries.firstOrNull { it.persistedValue == manifest.mediaType }
    require(mediaType != null && mediaType != MediaType.ANIME) {
        "Unsupported generic catalog media type: ${manifest.mediaType}"
    }
    require(manifest.provider.isNotBlank()) { "Catalog provider must not be blank" }
    require(manifest.catalogVersion.isNotBlank()) { "Catalog version must not be blank" }
    require(manifest.sha256.matches(Regex("[0-9a-f]{64}"))) {
        "Catalog SHA-256 must be lowercase hexadecimal"
    }
    require(manifest.itemCount >= 0) { "Catalog item count must not be negative" }
    return mediaType
}

internal fun validateMediaCatalogRecord(
    record: MediaCatalogRecord,
    manifest: MediaCatalogManifest
) {
    require(record.id.isNotBlank()) { "Catalog record ID must not be blank" }
    require(record.provider.isNotBlank()) { "Catalog record provider must not be blank" }
    require(record.providerId.isNotBlank()) { "Catalog record provider ID must not be blank" }
    require(record.title.isNotBlank()) { "Catalog record title must not be blank" }
    val mediaType = MediaType.entries.firstOrNull { it.persistedValue == record.mediaType }
    require(mediaType != null && mediaType != MediaType.ANIME) {
        "Unsupported generic catalog record media type: ${record.mediaType}"
    }
    require(record.mediaType == manifest.mediaType) {
        "Catalog record media type ${record.mediaType} does not match ${manifest.mediaType}"
    }
    require(record.provider == manifest.provider) {
        "Catalog record provider ${record.provider} does not match ${manifest.provider}"
    }
    require(record.id.startsWith("${mediaType.persistedValue}:")) {
        "Catalog record ID ${record.id} has the wrong media type prefix"
    }
    require(record.popularity == null || record.popularity.isFinite()) {
        "Catalog record popularity must be finite"
    }
}
