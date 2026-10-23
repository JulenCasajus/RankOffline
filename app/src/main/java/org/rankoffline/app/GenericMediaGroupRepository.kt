package org.rankoffline.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GenericGroupRatingSave(
    val rating: MediaGroupRatingEntity,
    val scores: List<MediaGroupRatingScoreEntity>
)

internal fun buildGenericElaborateGroupRating(
    packId: String,
    groupId: String,
    draft: RatingDraft,
    config: RatingSystemConfig,
    finalScoreOverride: Double? = null,
    profileId: String? = null,
    updatedAt: Long
): GenericGroupRatingSave {
    val individual = buildGenericElaborateRating(
        packId = packId,
        mediaId = groupId,
        draft = draft,
        config = config,
        finalScoreOverride = finalScoreOverride,
        profileId = profileId,
        updatedAt = updatedAt
    )
    return GenericGroupRatingSave(
        rating = MediaGroupRatingEntity(
            groupId = groupId,
            additiveScore = individual.rating.additiveScore,
            qualityScore = individual.rating.qualityScore,
            finalScore = individual.rating.finalScore,
            notes = individual.rating.notes,
            status = individual.rating.status,
            updatedAt = individual.rating.updatedAt,
            profileId = individual.rating.profileId,
            packId = packId
        ),
        scores = individual.scores.map { score ->
            MediaGroupRatingScoreEntity(
                groupId = groupId,
                categoryId = score.categoryId,
                score = score.score,
                applicable = score.applicable,
                packId = packId
            )
        }
    )
}

internal fun buildGenericSimpleGroupRating(
    packId: String,
    groupId: String,
    draft: RatingDraft,
    qualityScore: Double,
    finalScore: Double,
    updatedAt: Long
): MediaGroupRatingEntity {
    val individual = buildGenericSimpleRating(packId, groupId, draft, qualityScore, finalScore, updatedAt)
    return MediaGroupRatingEntity(
        groupId = groupId,
        additiveScore = individual.additiveScore,
        qualityScore = individual.qualityScore,
        finalScore = individual.finalScore,
        notes = individual.notes,
        status = individual.status,
        updatedAt = individual.updatedAt,
        profileId = individual.profileId,
        packId = packId
    )
}

class GenericMediaGroupRepository(
    database: AnimeDatabase,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val dao = database.mediaGroupDao()

    suspend fun upsertGroup(
        group: MediaGroupEntity,
        members: List<MediaGroupMemberEntity>
    ) = withContext(Dispatchers.IO) {
        requireGenericMediaType(group.mediaType)
        members.forEach { member ->
            require(member.groupId == group.id) { "Member belongs to a different group: ${member.mediaId}" }
            require(dao.memberMediaType(member.mediaId) == group.mediaType) {
                "Group and member media types must match: ${member.mediaId}"
            }
        }
        val orderedMembers = members.sortedWith(compareBy({ it.position }, { it.mediaId }))
        dao.upsertGroupWithMembers(
            group.copy(
                representativeMediaId = group.representativeMediaId
                    ?: orderedMembers.firstOrNull()?.mediaId,
                memberCount = orderedMembers.size
            ),
            orderedMembers
        )
    }

    suspend fun groupDetail(groupId: String): MediaGroupDetail? = withContext(Dispatchers.IO) {
        val group = dao.group(groupId) ?: return@withContext null
        val packId = packId(group.mediaType)
        val ratings = dao.ratedMemberRatings(packId, groupId)
        val derived = deriveMediaGroupRating(ratings, dao.ratedMemberScores(packId, groupId))
        val ratingsById = ratings.associateBy { it.mediaId }
        val members = dao.groupMembers(groupId).map { item -> item.toDomain(ratingsById[item.id]) }
        val firstMemberId = members.firstOrNull()?.id
        MediaGroupDetail(
            group = group.toDomain(),
            members = members.map { it.copy(sharedCoverMediaId = firstMemberId) },
            explicitRating = dao.groupRating(packId, groupId)?.takeIf { it.finalScore?.isFinite() == true },
            derivedRating = derived
        )
    }

    suspend fun loadGroupRating(groupId: String, fromMembers: Boolean): RatingDraft =
        withContext(Dispatchers.IO) {
            val packId = requireGenericGroup(groupId).toPackId().value
            val rating = dao.groupRating(packId, groupId)?.takeIf { it.finalScore?.isFinite() == true }
            if (rating == null) {
                return@withContext if (fromMembers) {
                    deriveMediaGroupRating(
                        dao.ratedMemberRatings(packId, groupId),
                        dao.ratedMemberScores(packId, groupId)
                    ).asDraft()
                } else {
                    RatingDraft()
                }
            }
            RatingDraft(
                categoryScores = dao.groupRatingScores(packId, groupId).associate { score ->
                    score.categoryId to score.score.takeIf { score.applicable }
                },
                additiveScore = rating.additiveScore,
                notes = rating.notes,
                status = rating.status,
                finalScore = rating.finalScore,
                qualityScore = rating.qualityScore,
                profileId = rating.profileId
            )
        }

    suspend fun groupRatingScores(groupId: String): List<MediaGroupRatingScoreEntity> =
        withContext(Dispatchers.IO) {
            val packId = requireGenericGroup(groupId).toPackId().value
            dao.groupRatingScores(packId, groupId)
        }

    suspend fun saveSimpleGroupRating(
        groupId: String,
        draft: RatingDraft,
        qualityScore: Double,
        finalScore: Double
    ) = withContext(Dispatchers.IO) {
        val packId = requireGenericGroup(groupId).toPackId().value
        dao.upsertGroupRating(
            buildGenericSimpleGroupRating(packId, groupId, draft, qualityScore, finalScore, now())
        )
    }

    suspend fun saveElaborateGroupRating(
        groupId: String,
        draft: RatingDraft,
        config: RatingSystemConfig,
        finalScoreOverride: Double? = null,
        profileId: String? = null
    ) = withContext(Dispatchers.IO) {
        val packId = requireGenericGroup(groupId).toPackId().value
        val save = buildGenericElaborateGroupRating(
            packId,
            groupId,
            draft,
            config,
            finalScoreOverride,
            profileId,
            now()
        )
        dao.replaceGroupRatingAndScores(save.rating, save.scores)
    }

    suspend fun deleteGroupRating(groupId: String) = withContext(Dispatchers.IO) {
        val packId = requireGenericGroup(groupId).toPackId().value
        dao.deleteGroupRating(packId, groupId)
    }

    suspend fun deleteGroupRatingCategory(mediaType: MediaType, categoryId: String) =
        withContext(Dispatchers.IO) {
            requireGenericMediaType(mediaType.persistedValue)
            dao.deleteGroupRatingScoresForCategory(mediaType.toPackId().value, categoryId)
        }

    suspend fun deleteGroupRatingScoresOutsideCategories(
        mediaType: MediaType,
        categoryIds: List<String>
    ) = withContext(Dispatchers.IO) {
        requireGenericMediaType(mediaType.persistedValue)
        if (categoryIds.isEmpty()) {
            dao.deleteAllGroupRatingScores(mediaType.toPackId().value)
        } else {
            dao.deleteGroupRatingScoresOutsideCategories(mediaType.toPackId().value, categoryIds)
        }
    }

    private suspend fun requireGenericGroup(groupId: String): MediaType {
        val persistedType = requireNotNull(dao.groupMediaType(groupId)) { "Unknown media group: $groupId" }
        requireGenericMediaType(persistedType)
        return requireNotNull(MediaType.entries.firstOrNull { it.persistedValue == persistedType })
    }

    private fun packId(persistedMediaType: String): String =
        requireNotNull(MediaType.entries.firstOrNull { it.persistedValue == persistedMediaType })
            .toPackId().value
}
