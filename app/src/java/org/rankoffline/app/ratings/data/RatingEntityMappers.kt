package org.rankoffline.app

internal fun draftOf(
    bonus: Double,
    quality: Double?,
    final: Double?,
    notes: String,
    status: String,
    profile: String?,
    categoryScores: Map<String, Double?>
) = RatingDraft(categoryScores, bonus, notes, status, final, quality, profile)

internal fun PreparedRating.toItemEntity(key: CatalogEntryKey) = MediaRatingEntity(
    packId = key.packId.value,
    mediaId = key.id,
    additiveScore = additiveScore,
    qualityScore = qualityScore,
    finalScore = finalScore,
    notes = notes,
    status = status,
    updatedAt = updatedAt,
    profileId = profileId
)

internal fun PreparedRating.toGroupEntity(key: CatalogEntryKey) = MediaGroupRatingEntity(
    packId = key.packId.value,
    groupId = key.id,
    additiveScore = additiveScore,
    qualityScore = qualityScore,
    finalScore = finalScore,
    notes = notes,
    status = status,
    updatedAt = updatedAt,
    profileId = profileId
)
