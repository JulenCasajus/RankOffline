package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

/** SQL contract extracted from MediaDao; methods inherited by its @Dao facade. */
interface MediaQueryDao {
    /** Search/merge is a SELECT-only projection; pack data and relations never change. */
    @RawQuery(observedEntities = [MediaItemEntity::class, MediaItemAttributeEntity::class,
        MediaAliasEntity::class, MediaGroupEntity::class, MediaGroupMemberEntity::class,
        MediaRatingEntity::class, MediaGroupRatingEntity::class])
    suspend fun visibleEntries(query: SupportSQLiteQuery): List<CatalogEntryIdRow>


    @Query("SELECT * FROM media_item WHERE pack_id = :packId AND id = :id")
    suspend fun item(packId: Long, id: String): MediaItemEntity?


    @Query("SELECT * FROM media_item WHERE pack_id = :packId AND id IN (:ids)")
    suspend fun itemsByIds(packId: Long, ids: List<String>): List<MediaItemEntity>


    @Query("SELECT * FROM media_item_attribute WHERE pack_id = :packId AND media_id IN (:ids) ORDER BY media_id, field_id, position")
    suspend fun itemAttributes(packId: Long, ids: List<String>): List<MediaItemAttributeEntity>


    @Query("SELECT * FROM media_alias WHERE pack_id = :packId AND media_id IN (:ids)")
    suspend fun aliases(packId: Long, ids: List<String>): List<MediaAliasEntity>

}
