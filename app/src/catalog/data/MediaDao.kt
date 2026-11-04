package org.rankoffline.app

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query

/** Identity returned by a combined catalog/ranking query; namespaced, including entry kind. */
data class CatalogEntryIdRow(
    @ColumnInfo(name = "pack_id") val packId: Long,
    val id: String,
    @ColumnInfo(name = "entry_kind") val entryKind: String,
    @ColumnInfo(name = "final_score") val finalScore: Double?,
    @ColumnInfo(name = "quality_score") val qualityScore: Double?,
    @ColumnInfo(name = "additive_score") val additiveScore: Double?,
    @ColumnInfo(name = "category_score") val categoryScore: Double?,
    @ColumnInfo(name = "member_count") val memberCount: Int,
    @ColumnInfo(name = "rated_count") val ratedCount: Int,
    @ColumnInfo(name = "has_explicit_score") val hasExplicitScore: Boolean
)

data class MediaCoverPathRow(
    @ColumnInfo(name = "pack_id") val packId: Long,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "local_cover_path") val localCoverPath: String?
)

/** One lightweight Room projection: the path and its persisted resolution belong together. */
data class CoverSnapshotRow(
    @ColumnInfo(name = "local_cover_path") val localPath: String?,
    @ColumnInfo(name = "cover_resolution_state") val resolutionState: String,
    @ColumnInfo(name = "remote_cover_ref") val remoteRef: String? = null
)

@Dao
interface MediaDao : MediaQueryDao, CatalogFilterDao, ItemCoverDao, ItemRatingDao {
    @Query("SELECT * FROM media_rating WHERE pack_id = :packId AND media_id IN (:ids)")
    suspend fun ratingsByIds(packId: Long, ids: List<String>): List<MediaRatingEntity>

}
