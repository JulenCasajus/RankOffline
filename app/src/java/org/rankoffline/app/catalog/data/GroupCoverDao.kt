package org.rankoffline.app

import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** SQL contract extracted from MediaGroupDao; methods inherited by its @Dao facade. */
interface GroupCoverDao {
    @Query("SELECT pack_id, id AS group_id, local_cover_path FROM media_group WHERE pack_id = :packId AND id IN (:ids)")
    suspend fun groupCoverPaths(packId: Long, ids: List<String>): List<MediaGroupCoverPathRow>


    @Query("SELECT local_cover_path FROM media_group WHERE pack_id = :packId AND local_cover_path IS NOT NULL")
    fun observeLocalCoverPaths(packId: Long): Flow<List<String>>


    @Query("SELECT local_cover_path, cover_resolution_state, remote_cover_ref FROM media_group WHERE pack_id = :packId AND id = :groupId")
    fun observeCoverSnapshot(packId: Long, groupId: String): Flow<CoverSnapshotRow?>


    @Query("""UPDATE media_group
        SET local_cover_path = :newPath, cover_resolution_state = :newState
        WHERE pack_id = :packId AND id = :groupId
          AND remote_cover_ref IS :expectedRemoteRef
          AND local_cover_path IS :expectedLocalPath""")
    suspend fun compareAndSetCover(
        packId: Long,
        groupId: String,
        expectedRemoteRef: String?,
        expectedLocalPath: String?,
        newPath: String?,
        newState: String
    ): Int


    @Query("SELECT * FROM media_group WHERE pack_id = :packId AND local_cover_path IS NOT NULL")
    suspend fun groupsWithLocalCovers(packId: Long): List<MediaGroupEntity>


    @Query("UPDATE media_group SET local_cover_path = NULL, cover_resolution_state = 'UNKNOWN' WHERE pack_id = :packId AND local_cover_path IS NOT NULL")
    suspend fun clearLocalCovers(packId: Long): Int


    @Query("SELECT COUNT(*) FROM media_group WHERE pack_id = :packId AND cover_resolution_state = 'NOT_FOUND'")
    suspend fun notFoundGroupCoverCount(packId: Long): Long

}
