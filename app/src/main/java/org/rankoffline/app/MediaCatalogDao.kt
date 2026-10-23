package org.rankoffline.app

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

data class MediaCatalogItemFields(
    val id: String,
    @ColumnInfo(name = "media_type") val mediaType: String,
    val provider: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    val title: String,
    @ColumnInfo(name = "original_title") val originalTitle: String?,
    val year: Int?,
    @ColumnInfo(name = "start_date") val startDate: String?,
    val format: String?,
    val status: String?,
    @ColumnInfo(name = "original_language") val originalLanguage: String?,
    val demographic: String?,
    @ColumnInfo(name = "part_number") val partNumber: Int?,
    val popularity: Double?,
    @ColumnInfo(name = "popularity_rank") val popularityRank: Int?,
    @ColumnInfo(name = "remote_cover_ref") val remoteCoverRef: String?
)

@Dao
interface MediaCatalogDao {
    @Query("SELECT id FROM media_item WHERE id IN (:mediaIds)")
    suspend fun existingMediaIds(mediaIds: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewItems(items: List<MediaItemEntity>): List<Long>

    @Update(entity = MediaItemEntity::class)
    suspend fun updateCatalogFields(items: List<MediaCatalogItemFields>): Int

    @Query("DELETE FROM media_genre WHERE media_id IN (:mediaIds)")
    suspend fun deleteGenres(mediaIds: List<String>)

    @Query("DELETE FROM media_credit WHERE media_id IN (:mediaIds)")
    suspend fun deleteCredits(mediaIds: List<String>)

    @Query("DELETE FROM media_alias WHERE media_id IN (:mediaIds)")
    suspend fun deleteAliases(mediaIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGenres(genres: List<MediaGenreEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCredits(credits: List<MediaCreditEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAliases(aliases: List<MediaAliasEntity>)

    @Query(
        "SELECT metadata_value FROM media_catalog_metadata " +
            "WHERE media_type = :mediaType AND metadata_key = :key"
    )
    suspend fun metadata(mediaType: String, key: String): String?

    @Query("SELECT COUNT(*) FROM media_item WHERE media_type = :mediaType")
    suspend fun catalogItemCount(mediaType: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(metadata: List<MediaCatalogMetadataEntity>)

    @Query("DELETE FROM media_group WHERE media_type = :mediaType")
    suspend fun deleteCatalogGroups(mediaType: String): Int

    @Query("DELETE FROM media_item WHERE media_type = :mediaType")
    suspend fun deleteCatalogItems(mediaType: String): Int

    @Query("DELETE FROM media_catalog_metadata WHERE media_type = :mediaType")
    suspend fun deleteCatalogMetadata(mediaType: String): Int

    /** One-cycle bridge for covers downloaded by stable Anime-only releases. */
    @Query(
        "UPDATE media_item SET " +
            "local_cover_path = COALESCE(local_cover_path, " +
            "(SELECT anime.local_cover_path FROM anime WHERE anime.id = media_item.id)), " +
            "remote_cover_ref = COALESCE(remote_cover_ref, " +
            "(SELECT COALESCE(anime.remote_cover_url, anime.poster_url) FROM anime " +
            "WHERE anime.id = media_item.id)), " +
            "cover_resolution_state = CASE WHEN COALESCE(local_cover_path, " +
            "(SELECT anime.local_cover_path FROM anime WHERE anime.id = media_item.id)) IS NOT NULL " +
            "THEN 'AVAILABLE' ELSE cover_resolution_state END " +
            "WHERE media_type = 'anime' AND id IN (SELECT id FROM anime)"
    )
    suspend fun reconcileLegacyAnimeCoverMetadata(): Int

    /** Deletes provider/reference data only; detached Room 12 personal rows remain intact. */
    @Transaction
    suspend fun deleteCatalogReferenceData(mediaType: String) {
        deleteCatalogGroups(mediaType)
        deleteCatalogItems(mediaType)
        deleteCatalogMetadata(mediaType)
    }

    @Query("DELETE FROM media_rating WHERE pack_id = :packId")
    suspend fun deletePersonalRatings(packId: String): Int

    @Query("DELETE FROM media_group_rating WHERE pack_id = :packId")
    suspend fun deletePersonalGroupRatings(packId: String): Int

    /** Explicit destructive action scoped by canonical PackId, independent of provider ID shape. */
    @Transaction
    suspend fun deletePersonalData(packId: String) {
        deletePersonalRatings(packId)
        deletePersonalGroupRatings(packId)
    }

    /** Atomic destructive boundary for one generic official pack. */
    @Transaction
    suspend fun purgePackData(mediaType: String, packId: String) {
        deletePersonalData(packId)
        deleteCatalogReferenceData(mediaType)
    }
}
