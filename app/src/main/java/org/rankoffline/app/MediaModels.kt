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

data class MediaGroup(
    val id: String,
    val mediaType: MediaType,
    val groupType: String,
    val provider: String?,
    val providerGroupId: String?,
    val displayTitle: String,
    val representativeMediaId: String?,
    val remoteCoverRef: String?,
    val localCoverPath: String?,
    val coverResolutionState: CoverResolutionState
)

data class MediaGroupDetail(
    val group: MediaGroup,
    val members: List<MediaItem>,
    val explicitRating: MediaGroupRatingEntity?,
    val derivedRating: DerivedGroupRating
)

sealed interface MediaListEntry {
    val id: String
    val mediaType: MediaType
    val title: String
    val finalScore: Double?
    val qualityScore: Double?
    val additiveScore: Double?
    val categoryScore: Double?

    data class Item(val value: MediaItem) : MediaListEntry {
        override val id get() = value.id
        override val mediaType get() = value.mediaType
        override val title get() = value.title
        override val finalScore get() = value.finalScore
        override val qualityScore get() = value.qualityScore
        override val additiveScore get() = value.additiveScore
        override val categoryScore get() = value.categoryScore
    }

    data class Group(
        val value: MediaGroup,
        override val finalScore: Double?,
        override val qualityScore: Double?,
        override val additiveScore: Double?,
        override val categoryScore: Double?,
        val popularity: Double?,
        val memberCount: Int,
        val ratedCount: Int,
        val representativeMediaId: String?,
        val derived: Boolean
    ) : MediaListEntry {
        override val id get() = value.id
        override val mediaType get() = value.mediaType
        override val title get() = value.displayTitle
    }
}

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

fun MediaListEntry.toAnimeDisplay(): Anime = when (this) {
    is MediaListEntry.Item -> Anime(
        id = value.id,
        title = value.title,
        mediaType = value.mediaType,
        popularity = value.popularity?.toInt(),
        popularityRank = value.popularityRank,
        finalScore = value.finalScore,
        baseScore = value.qualityScore,
        additiveScore = value.additiveScore,
        categoryScore = value.categoryScore,
        status = value.status,
        localCoverPath = value.localCoverPath,
        coverResolutionState = value.coverResolutionState,
        format = value.format,
        seasonYear = value.year,
        startDate = value.startDate
    )
    is MediaListEntry.Group -> Anime(
        id = value.id,
        title = value.displayTitle,
        mediaType = value.mediaType,
        popularity = popularity?.toInt(),
        finalScore = finalScore,
        baseScore = qualityScore,
        additiveScore = additiveScore,
        categoryScore = categoryScore,
        localCoverPath = value.localCoverPath,
        coverResolutionState = value.coverResolutionState,
        groupId = value.id,
        groupType = value.groupType,
        rootAnimeId = representativeMediaId,
        memberCount = memberCount,
        ratedCount = ratedCount,
        derived = derived
    )
}

fun MediaGroupEntity.toDomain(): MediaGroup = MediaGroup(
    id = id,
    mediaType = MediaType.fromPersistedValue(mediaType),
    groupType = groupType,
    provider = provider,
    providerGroupId = providerGroupId,
    displayTitle = displayTitle,
    representativeMediaId = representativeMediaId,
    remoteCoverRef = remoteCoverRef,
    localCoverPath = localCoverPath,
    coverResolutionState = runCatching { CoverResolutionState.valueOf(coverResolutionState) }
        .getOrDefault(CoverResolutionState.UNKNOWN)
)
