package org.rankoffline.app

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Transaction

/** Catalog metadata only: preserves independently downloaded cover fields. */
data class MediaGroupCatalogFields(
    @ColumnInfo(name = "pack_id") val packId: Long,
    val id: String,
    @ColumnInfo(name = "group_type") val groupType: String,
    @ColumnInfo(name = "display_title") val displayTitle: String,
    val provider: String?,
    @ColumnInfo(name = "provider_group_id") val providerGroupId: String?,
    @ColumnInfo(name = "group_popularity") val popularity: Double?,
    @ColumnInfo(name = "group_popularity_rank") val popularityRank: Int?,
    @ColumnInfo(name = "remote_cover_ref") val remoteCoverRef: String?
)

data class MediaGroupCoverPathRow(
    @ColumnInfo(name = "pack_id") val packId: Long,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "local_cover_path") val localCoverPath: String?
)

/** Only catalog-declared membership determines the optional group cover for an item. */
data class PreferredCoverGroupRow(
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "group_id") val groupId: String
)

/** Groups are catalog-supplied relations; the DAO does not infer or generate them. */
@Dao
interface MediaGroupDao : GroupQueryDao, GroupRelationDao, GroupImportDao, GroupCoverDao, GroupRatingReadDao, GroupRatingWriteDao {
    @Transaction
    suspend fun replaceRelations(
        packId: Long,
        groupIds: List<String>,
        members: List<MediaGroupMemberEntity>,
        representatives: List<MediaGroupRepresentativeEntity>
    ) {
        require(groupIds.size == groupIds.distinct().size)
        require(members.all { it.packId == packId && it.groupId in groupIds && it.position >= 0 })
        require(representatives.all { it.packId == packId && it.groupId in groupIds })
        require(representatives.map { it.groupId }.distinct().size == representatives.size)
        groupIds.chunked(500).forEach {
            deleteRepresentatives(packId, it)
            deleteMemberships(packId, it)
        }
        members.chunked(500).forEach { insertMemberships(it) }
        representatives.chunked(500).forEach { insertRepresentatives(it) }
    }

    /** Used only when a future manifest declares group-specific attributes. */
    @Transaction
    suspend fun replaceAttributes(packId: Long, groupIds: List<String>, values: List<MediaGroupAttributeEntity>) {
        require(groupIds.size == groupIds.distinct().size)
        require(values.all { it.packId == packId && it.groupId in groupIds })
        groupIds.chunked(500).forEach { deleteAttributes(packId, it) }
        values.chunked(500).forEach { insertAttributes(it) }
    }

    @Transaction
    suspend fun saveRating(value: MediaGroupRatingEntity, scores: List<MediaGroupRatingScoreEntity>) {
        require(scores.all { it.packId == value.packId && it.groupId == value.groupId })
        upsertRating(value)
        deleteRatingScores(value.packId, value.groupId)
        if (scores.isNotEmpty()) insertRatingScores(scores)
    }

}

/** Shared indexed SQL for on-demand group fallback and its reactive UI observation. */
internal const val GROUP_COVER_CANDIDATES_SQL = """
    SELECT i.* FROM (
        SELECT media_id, MIN(priority) AS priority, MIN(position) AS position
        FROM (
            SELECT media_id, 1 AS priority, position FROM media_group_member
                WHERE pack_id=:packId AND group_id=:groupId
            UNION ALL
            SELECT media_id, 0 AS priority, -1 AS position FROM media_group_representative
                WHERE pack_id=:packId AND group_id=:groupId
        ) candidate_refs GROUP BY media_id
    ) c
    CROSS JOIN media_item i
    WHERE i.pack_id=:packId AND i.id=c.media_id
    ORDER BY c.priority, c.position, i.id
"""
