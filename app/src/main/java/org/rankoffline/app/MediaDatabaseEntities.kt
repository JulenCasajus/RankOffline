package org.rankoffline.app

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_item",
    indices = [
        Index(value = ["media_type"], name = "idx_media_item_media_type"),
        Index(value = ["media_type", "title"], name = "idx_media_item_type_title"),
        Index(value = ["media_type", "popularity_rank"], name = "idx_media_item_type_popularity_rank"),
        Index(value = ["media_type", "year"], name = "idx_media_item_type_year"),
        Index(value = ["media_type", "format"], name = "idx_media_item_type_format"),
        Index(value = ["media_type", "demographic"], name = "idx_media_item_type_demographic"),
        Index(
            value = ["media_type", "provider", "provider_id"],
            name = "idx_media_item_provider_identity",
            unique = true
        )
    ]
)
data class MediaItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "media_type") val mediaType: String,
    val provider: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    val title: String,
    @ColumnInfo(name = "original_title") val originalTitle: String? = null,
    val year: Int? = null,
    @ColumnInfo(name = "start_date") val startDate: String? = null,
    val format: String? = null,
    val status: String? = null,
    @ColumnInfo(name = "original_language") val originalLanguage: String? = null,
    val demographic: String? = null,
    @ColumnInfo(name = "part_number") val partNumber: Int? = null,
    val popularity: Double? = null,
    @ColumnInfo(name = "popularity_rank") val popularityRank: Int? = null,
    @ColumnInfo(name = "remote_cover_ref") val remoteCoverRef: String? = null,
    @ColumnInfo(name = "local_cover_path") val localCoverPath: String? = null,
    @ColumnInfo(name = "cover_resolution_state", defaultValue = "'UNKNOWN'")
    val coverResolutionState: String = "UNKNOWN"
)

@Entity(
    tableName = "media_genre",
    primaryKeys = ["media_id", "genre"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["genre"], name = "idx_media_genre_genre")]
)
data class MediaGenreEntity(
    @ColumnInfo(name = "media_id") val mediaId: String,
    val genre: String
)

@Entity(
    tableName = "media_credit",
    primaryKeys = ["media_id", "role", "name"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["role", "name"], name = "idx_media_credit_role_name")]
)
data class MediaCreditEntity(
    @ColumnInfo(name = "media_id") val mediaId: String,
    val role: String,
    val name: String
)

@Entity(
    tableName = "media_alias",
    primaryKeys = ["media_id", "title"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["title"], name = "idx_media_alias_title")]
)
data class MediaAliasEntity(
    @ColumnInfo(name = "media_id") val mediaId: String,
    val title: String,
    val language: String? = null,
    val kind: String? = null
)

@Entity(
    tableName = "media_group",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["representative_media_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["media_type", "group_type"], name = "idx_media_group_type"),
        Index(value = ["representative_media_id"], name = "idx_media_group_representative"),
        Index(
            value = ["media_type", "group_type", "provider", "provider_group_id"],
            name = "idx_media_group_provider_identity",
            unique = true
        )
    ]
)
data class MediaGroupEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "group_type") val groupType: String,
    val provider: String? = null,
    @ColumnInfo(name = "provider_group_id") val providerGroupId: String? = null,
    @ColumnInfo(name = "display_title") val displayTitle: String,
    @ColumnInfo(name = "representative_media_id") val representativeMediaId: String? = null,
    @ColumnInfo(name = "remote_cover_ref") val remoteCoverRef: String? = null,
    @ColumnInfo(name = "local_cover_path") val localCoverPath: String? = null,
    @ColumnInfo(name = "cover_resolution_state", defaultValue = "'UNKNOWN'")
    val coverResolutionState: String = "UNKNOWN"
)

@Entity(
    tableName = "media_group_member",
    primaryKeys = ["group_id", "media_id"],
    foreignKeys = [
        ForeignKey(
            entity = MediaGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["media_id"], name = "idx_media_group_member_media")]
)
data class MediaGroupMemberEntity(
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    val position: Int
)

@Entity(
    tableName = "media_rating",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MediaRatingEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "additive_score") val additiveScore: Double,
    @ColumnInfo(name = "quality_score") val qualityScore: Double?,
    @ColumnInfo(name = "final_score") val finalScore: Double?,
    val notes: String,
    val status: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "profile_id") val profileId: String? = null
)

@Entity(
    tableName = "media_rating_score",
    primaryKeys = ["media_id", "category_id"],
    foreignKeys = [
        ForeignKey(
            entity = MediaRatingEntity::class,
            parentColumns = ["media_id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MediaRatingScoreEntity(
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    val score: Double?,
    val applicable: Boolean
)

@Entity(
    tableName = "media_group_rating",
    foreignKeys = [
        ForeignKey(
            entity = MediaGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MediaGroupRatingEntity(
    @PrimaryKey @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "additive_score") val additiveScore: Double,
    @ColumnInfo(name = "quality_score") val qualityScore: Double?,
    @ColumnInfo(name = "final_score") val finalScore: Double?,
    val notes: String,
    val status: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "profile_id") val profileId: String? = null
)

@Entity(
    tableName = "media_group_rating_score",
    primaryKeys = ["group_id", "category_id"],
    foreignKeys = [
        ForeignKey(
            entity = MediaGroupRatingEntity::class,
            parentColumns = ["group_id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MediaGroupRatingScoreEntity(
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    val score: Double?,
    val applicable: Boolean
)

@Entity(tableName = "media_catalog_metadata", primaryKeys = ["media_type", "metadata_key"])
data class MediaCatalogMetadataEntity(
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "metadata_key") val metadataKey: String,
    @ColumnInfo(name = "metadata_value") val metadataValue: String
)
