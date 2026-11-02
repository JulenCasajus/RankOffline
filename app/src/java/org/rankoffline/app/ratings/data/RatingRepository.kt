package org.rankoffline.app

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One personal-rating API for items and groups; pack catalog rows are not required to exist. */
class RatingRepository(
    private val database: RankOfflineDatabase,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val items = database.mediaDao()
    private val groups = database.mediaGroupDao()

    /** A scoring criterion is independent of item/group and of a pack's catalog attributes. */
    data class CategoryScore(val categoryId: String, val score: Double?, val applicable: Boolean)

    /** Null means no explicit rating. A notes-only rating is NOT null. */
    suspend fun ratingDraftOrNull(key: CatalogEntryKey): RatingDraft? = withContext(Dispatchers.IO) {
        database.withTransaction { explicitDraft(key) }
    }

    /** Optional group-member average is an editor suggestion; never saved automatically. */
    suspend fun loadRating(key: CatalogEntryKey, fromMembers: Boolean = false): RatingDraft =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                explicitDraft(key) ?: if (fromMembers && key.kind == CatalogEntryKind.GROUP &&
                    groups.group(key.packId.value, key.id) != null
                ) {
                    deriveMediaGroupRating(
                        groups.ratedMemberRatings(key.packId.value, key.id),
                        groups.ratedMemberScores(key.packId.value, key.id)
                    ).asDraft()
                } else RatingDraft()
            }
        }

    suspend fun ratingScores(key: CatalogEntryKey): List<CategoryScore> = withContext(Dispatchers.IO) {
        val pack = key.packId.value
        when (key.kind) {
            CatalogEntryKind.ITEM -> items.ratingScores(pack, key.id).map {
                CategoryScore(it.categoryId, it.score, it.applicable)
            }
            CatalogEntryKind.GROUP -> groups.ratingScores(pack, key.id).map {
                CategoryScore(it.categoryId, it.score, it.applicable)
            }
        }
    }

    /** Simple mode retains previously saved per-category scores, as in the original editor. */
    suspend fun saveSimpleRating(
        key: CatalogEntryKey, draft: RatingDraft, qualityScore: Double, finalScore: Double
    ) = withContext(Dispatchers.IO) {
        val prepared = prepareSimpleRating(draft, qualityScore, finalScore, now())
        when (key.kind) {
            CatalogEntryKind.ITEM -> items.upsertRating(prepared.toItemEntity(key))
            CatalogEntryKind.GROUP -> groups.upsertRating(prepared.toGroupEntity(key))
        }
    }

    /** Replace all category scores atomically, including explicit N/A values (applicable=false). */
    suspend fun saveElaborateRating(
        key: CatalogEntryKey,
        draft: RatingDraft,
        config: RatingSystemConfig,
        finalScoreOverride: Double? = null,
        profileId: String? = null
    ) = withContext(Dispatchers.IO) {
        val prepared = prepareElaborateRating(draft, config, finalScoreOverride, profileId, now())
        val scores = requireNotNull(prepared.categoryScores)
        when (key.kind) {
            CatalogEntryKind.ITEM -> items.saveRating(
                prepared.toItemEntity(key), scores.map { (id, value) ->
                    MediaRatingScoreEntity(key.packId.value, key.id, id, value, value != null)
                }
            )
            CatalogEntryKind.GROUP -> groups.saveRating(
                prepared.toGroupEntity(key), scores.map { (id, value) ->
                    MediaGroupRatingScoreEntity(key.packId.value, key.id, id, value, value != null)
                }
            )
        }
    }

    /** Notes and status are metadata: updating them never rewrites any score or profile. */
    suspend fun saveMetadata(key: CatalogEntryKey, notes: String, status: String) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val packId = key.packId.value
                val updatedAt = now()
                when (key.kind) {
                    CatalogEntryKind.ITEM -> {
                        val current = items.rating(packId, key.id)
                        items.upsertRating(current?.copy(notes = notes, status = status, updatedAt = updatedAt)
                            ?: MediaRatingEntity(packId, key.id, 0.0, null, null, notes, status, updatedAt))
                    }
                    CatalogEntryKind.GROUP -> {
                        val current = groups.rating(packId, key.id)
                        groups.upsertRating(current?.copy(notes = notes, status = status, updatedAt = updatedAt)
                            ?: MediaGroupRatingEntity(packId, key.id, 0.0, null, null, notes, status, updatedAt))
                    }
                }
            }
        }

    /** Only personal data is deleted, never a pack-defined item/group or its membership. */
    suspend fun deleteRating(key: CatalogEntryKey): Boolean = withContext(Dispatchers.IO) {
        when (key.kind) {
            CatalogEntryKind.ITEM -> items.deleteRating(key.packId.value, key.id) > 0
            CatalogEntryKind.GROUP -> groups.deleteRating(key.packId.value, key.id) > 0
        }
    }

    /** A rating criterion belongs to the pack's rating system, so clean BOTH entry kinds. */
    suspend fun deleteCategoryScores(packId: PackId, categoryId: String): Int =
        withContext(Dispatchers.IO) {
            require(categoryId.isNotBlank())
            database.withTransaction {
                items.deleteRatingScoresForCategory(packId.value, categoryId) +
                    groups.deleteRatingScoresForCategory(packId.value, categoryId)
            }
        }

    /** Empty input may represent missing settings. Never silently delete all personal scores. */
    suspend fun retainCategoryScores(packId: PackId, retainedCategoryIds: Set<String>): Int =
        withContext(Dispatchers.IO) {
            require(retainedCategoryIds.isNotEmpty()) { "Cannot remove every rating category implicitly" }
            require(retainedCategoryIds.all(String::isNotBlank))
            database.withTransaction {
                val categories = retainedCategoryIds.sorted()
                items.deleteRatingScoresOutsideCategories(packId.value, categories) +
                    groups.deleteRatingScoresOutsideCategories(packId.value, categories)
            }
        }

    private suspend fun explicitDraft(key: CatalogEntryKey): RatingDraft? {
        val pack = key.packId.value
        return when (key.kind) {
            CatalogEntryKind.ITEM -> items.rating(pack, key.id)?.let { rating ->
                draftOf(rating.additiveScore, rating.qualityScore, rating.finalScore, rating.notes,
                    rating.status, rating.profileId,
                    items.ratingScores(pack, key.id).associate { it.categoryId to (if (it.applicable) it.score else null) })
            }
            CatalogEntryKind.GROUP -> groups.rating(pack, key.id)?.let { rating ->
                draftOf(rating.additiveScore, rating.qualityScore, rating.finalScore, rating.notes,
                    rating.status, rating.profileId,
                    groups.ratingScores(pack, key.id).associate { it.categoryId to (if (it.applicable) it.score else null) })
            }
        }
    }
}
