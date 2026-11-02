package org.rankoffline.app

import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** SQL contract extracted from MediaDao; methods inherited by its @Dao facade. */
interface ItemCoverDao {
    @Query("SELECT * FROM media_item WHERE pack_id = :packId AND local_cover_path IS NOT NULL")
    suspend fun itemsWithLocalCovers(packId: Long): List<MediaItemEntity>


    @Query("SELECT pack_id, id AS media_id, local_cover_path FROM media_item WHERE pack_id = :packId AND id IN (:ids)")
    suspend fun coverPaths(packId: Long, ids: List<String>): List<MediaCoverPathRow>


    @Query("SELECT local_cover_path FROM media_item WHERE pack_id = :packId AND local_cover_path IS NOT NULL")
    fun observeLocalCoverPaths(packId: Long): Flow<List<String>>


    @Query("SELECT local_cover_path, cover_resolution_state, remote_cover_ref FROM media_item WHERE pack_id = :packId AND id = :id")
    fun observeCoverSnapshot(packId: Long, id: String): Flow<CoverSnapshotRow?>


    @Query("SELECT COUNT(*) FROM media_item WHERE pack_id = :packId AND local_cover_path IS NOT NULL")
    fun observeDownloadedCoverCount(packId: Long): Flow<Int>


    @Query("""UPDATE media_item
        SET local_cover_path = :newPath, cover_resolution_state = :newState
        WHERE pack_id = :packId AND id = :id
          AND remote_cover_ref IS :expectedRemoteRef
          AND local_cover_path IS :expectedLocalPath""")
    suspend fun compareAndSetCover(
        packId: Long,
        id: String,
        expectedRemoteRef: String?,
        expectedLocalPath: String?,
        newPath: String?,
        newState: String
    ): Int


    @Query("UPDATE media_item SET local_cover_path = NULL, cover_resolution_state = 'UNKNOWN' WHERE pack_id = :packId AND local_cover_path IS NOT NULL")
    suspend fun clearLocalCovers(packId: Long): Int


    @Query("""SELECT i.* FROM media_item i
        INNER JOIN media_rating r ON r.pack_id = i.pack_id AND r.media_id = i.id
        WHERE i.pack_id = :packId AND r.final_score BETWEEN 0 AND 10
        ORDER BY r.final_score DESC, i.title COLLATE NOCASE ASC, i.id ASC
        LIMIT :limit""")
    suspend fun ratedCoverItems(packId: Long, limit: Int): List<MediaItemEntity>


    @Query("""SELECT * FROM media_item WHERE pack_id = :packId
        ORDER BY CASE WHEN popularity_rank IS NULL AND popularity IS NULL THEN 1 ELSE 0 END,
          CASE WHEN popularity_rank IS NULL THEN 1 ELSE 0 END,
          popularity_rank ASC, popularity DESC, title COLLATE NOCASE ASC, id ASC
        LIMIT :limit""")
    suspend fun topPopularCoverItems(packId: Long, limit: Int): List<MediaItemEntity>


    @Query("SELECT COUNT(*) FROM media_item WHERE pack_id = :packId AND cover_resolution_state = 'NOT_FOUND'")
    suspend fun notFoundCoverCount(packId: Long): Long

}
