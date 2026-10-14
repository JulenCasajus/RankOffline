package org.rankoffline.app

data class MediaItem(
    val id: String,
    val mediaType: MediaType,
    val provider: String? = null,
    val providerId: String? = null,
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
    val finalScore: Double? = null,
    val qualityScore: Double? = null,
    val additiveScore: Double? = null,
    val categoryScore: Double? = null,
    val remoteCoverRef: String? = null,
    val localCoverPath: String? = null,
    val coverResolutionState: CoverResolutionState = CoverResolutionState.UNKNOWN
)

fun MediaItemEntity.toDomain(
    rating: MediaRatingEntity? = null,
    categoryScore: Double? = null
): MediaItem = MediaItem(
    id = id,
    mediaType = MediaType.fromPersistedValue(mediaType),
    provider = provider,
    providerId = providerId,
    title = title,
    originalTitle = originalTitle,
    year = year,
    startDate = startDate,
    format = format,
    status = status,
    originalLanguage = originalLanguage,
    demographic = demographic,
    partNumber = partNumber,
    popularity = popularity,
    popularityRank = popularityRank,
    finalScore = rating?.finalScore,
    qualityScore = rating?.qualityScore,
    additiveScore = rating?.additiveScore,
    categoryScore = categoryScore,
    remoteCoverRef = remoteCoverRef,
    localCoverPath = localCoverPath,
    coverResolutionState = runCatching { CoverResolutionState.valueOf(coverResolutionState) }
        .getOrDefault(CoverResolutionState.UNKNOWN)
)

/** Transitional adapter: Anime keeps its published persistence while common UI uses [MediaItem]. */
fun Anime.toMediaItem(): MediaItem = MediaItem(
    id = id,
    mediaType = MediaType.ANIME,
    title = title,
    year = metadataYear(),
    startDate = startDate,
    format = format,
    popularity = popularity?.toDouble(),
    popularityRank = popularityRank,
    finalScore = finalScore,
    qualityScore = baseScore,
    additiveScore = additiveScore,
    categoryScore = categoryScore,
    remoteCoverRef = remoteCoverUrl,
    localCoverPath = localCoverPath,
    coverResolutionState = coverResolutionState
)
