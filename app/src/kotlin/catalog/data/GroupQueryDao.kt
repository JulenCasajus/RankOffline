package org.rankoffline.app

import androidx.room.ColumnInfo
import androidx.room.Query

data class GroupMemberAttributeRow(
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "field_id") val fieldId: String,
    @ColumnInfo(name = "value_text") val valueText: String?,
    @ColumnInfo(name = "value_number") val valueNumber: Double?,
    @ColumnInfo(name = "value_boolean") val valueBoolean: Boolean?
)

/** SQL contract extracted from MediaGroupDao; methods inherited by its @Dao facade. */
interface GroupQueryDao {
    @Query("SELECT * FROM media_group WHERE pack_id = :packId AND id = :id")
    suspend fun group(packId: Long, id: String): MediaGroupEntity?


    @Query("SELECT * FROM media_group WHERE pack_id = :packId AND id IN (:ids)")
    suspend fun groupsByIds(packId: Long, ids: List<String>): List<MediaGroupEntity>


    @Query("SELECT * FROM media_group_attribute WHERE pack_id = :packId AND group_id IN (:ids) ORDER BY group_id, field_id, position")
    suspend fun attributesForGroups(packId: Long, ids: List<String>): List<MediaGroupAttributeEntity>

    @Query("""SELECT m.group_id,a.field_id,a.value_text,a.value_number,a.value_boolean
        FROM media_group_member m JOIN media_item_attribute a
          ON a.pack_id=m.pack_id AND a.media_id=m.media_id
        WHERE m.pack_id=:packId AND m.group_id IN (:ids)
        ORDER BY m.group_id,m.position,a.position""")
    suspend fun memberAttributesForGroups(packId: Long, ids: List<String>): List<GroupMemberAttributeRow>


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
