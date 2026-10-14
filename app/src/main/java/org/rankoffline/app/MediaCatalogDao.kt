package org.rankoffline.app

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(metadata: List<MediaCatalogMetadataEntity>)
}
