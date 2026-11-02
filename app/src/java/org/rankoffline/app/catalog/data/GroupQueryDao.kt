package org.rankoffline.app

import androidx.room.Query

/** SQL contract extracted from MediaGroupDao; methods inherited by its @Dao facade. */
interface GroupQueryDao {
    @Query("SELECT * FROM media_group WHERE pack_id = :packId AND id = :id")
    suspend fun group(packId: Long, id: String): MediaGroupEntity?


    @Query("SELECT * FROM media_group WHERE pack_id = :packId AND id IN (:ids)")
    suspend fun groupsByIds(packId: Long, ids: List<String>): List<MediaGroupEntity>


    @Query("SELECT * FROM media_group_attribute WHERE pack_id = :packId AND group_id IN (:ids) ORDER BY group_id, field_id, position")
    suspend fun attributesForGroups(packId: Long, ids: List<String>): List<MediaGroupAttributeEntity>


    @Query("SELECT * FROM media_group_representative WHERE pack_id = :packId AND group_id IN (:ids)")
    suspend fun representativesByIds(packId: Long, ids: List<String>): List<MediaGroupRepresentativeEntity>


    @Query("SELECT * FROM media_group_member WHERE pack_id = :packId AND group_id = :groupId ORDER BY position, media_id")
    suspend fun members(packId: Long, groupId: String): List<MediaGroupMemberEntity>


    @Query("SELECT COUNT(*) FROM media_group_member WHERE pack_id = :packId AND group_id = :groupId")
    suspend fun memberCount(packId: Long, groupId: String): Int


    @Query("SELECT * FROM media_group_representative WHERE pack_id = :packId AND group_id = :groupId")
    suspend fun representative(packId: Long, groupId: String): MediaGroupRepresentativeEntity?


    @Query("SELECT * FROM media_group_attribute WHERE pack_id = :packId AND group_id = :groupId ORDER BY field_id, position")
    suspend fun attributes(packId: Long, groupId: String): List<MediaGroupAttributeEntity>


    @Query("SELECT COUNT(*) FROM media_group WHERE pack_id = :packId")
    suspend fun groupCountForPack(packId: Long): Long


    @Query("SELECT COUNT(*) FROM media_group_member WHERE pack_id = :packId")
    suspend fun membershipCountForPack(packId: Long): Long

}
