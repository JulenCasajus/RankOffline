package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** SQL contract extracted from MediaGroupDao; methods inherited by its @Dao facade. */
interface GroupRelationDao {
    @Query("""SELECT group_id AS groupId, media_id AS mediaId FROM (
        SELECT group_id, media_id, 1 AS priority, position FROM media_group_member
            WHERE pack_id=:packId AND group_id IN (:groupIds)
        UNION ALL
        SELECT group_id, media_id, 0 AS priority, -1 AS position FROM media_group_representative
            WHERE pack_id=:packId AND group_id IN (:groupIds)
    ) GROUP BY group_id, media_id
    ORDER BY group_id, MIN(priority), MIN(position), media_id""")
    suspend fun coverCandidateIds(packId: Long, groupIds: List<String>): List<GroupCoverCandidateIdRow>

    /** One indexed batch query per page; prefer lexical group ID on multi-membership. */
    @Query("""SELECT m.media_id, MIN(m.group_id) AS group_id
        FROM media_group_member m
        JOIN media_group g ON g.pack_id=m.pack_id AND g.id=m.group_id
        WHERE m.pack_id=:packId AND m.media_id IN (:mediaIds)
        GROUP BY m.media_id""")
    suspend fun preferredCoverGroups(packId: Long, mediaIds: List<String>): List<PreferredCoverGroupRow>


    /** Representative first, then group members. Fetches only referenced rows;
     * never scans the entire media_item table for a pack. */
    @Query(GROUP_COVER_CANDIDATES_SQL)
    suspend fun coverCandidateItems(packId: Long, groupId: String): List<MediaItemEntity>


    @Query(GROUP_COVER_CANDIDATES_SQL)
    fun observeCoverCandidateItems(packId: Long, groupId: String): Flow<List<MediaItemEntity>>


    @Query("DELETE FROM media_group_member WHERE pack_id = :packId AND group_id IN (:ids)")
    suspend fun deleteMemberships(packId: Long, ids: List<String>)


    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMemberships(values: List<MediaGroupMemberEntity>)


    @Query("DELETE FROM media_group_representative WHERE pack_id = :packId AND group_id IN (:ids)")
    suspend fun deleteRepresentatives(packId: Long, ids: List<String>)


    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRepresentatives(values: List<MediaGroupRepresentativeEntity>)

}

data class GroupCoverCandidateIdRow(val groupId: String, val mediaId: String)
