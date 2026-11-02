package org.rankoffline.app

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Catalog records are owned by a pack. An item ID is unique only inside its pack.
 * The pack manifest defines presentation, filter fields and grouping semantics.
 * Room stores data; it does not decide which kinds of packs are allowed.
 */
@Entity(
    tableName = "media_item",
    primaryKeys = ["pack_id", "id"],
    indices = [
        Index(value = ["pack_id", "title"], name = "idx_media_item_pack_title"),
        Index(value = ["pack_id", "popularity_rank"], name = "idx_media_item_pack_popularity_rank"),
        Index(value = ["pack_id", "cover_resolution_state"], name = "idx_media_item_pack_cover_state"),
        Index(
            value = ["pack_id", "provider", "provider_id"],
            name = "idx_media_item_pack_provider_identity",
            unique = true
        )
    ]
)
data class MediaItemEntity(
    @ColumnInfo(name = "pack_id") val packId: Long,
    val id: String,
    val title: String,
    /** Optional external provenance, not the primary key. */
    val provider: String? = null,
    @ColumnInfo(name = "provider_id") val providerId: String? = null,
    val popularity: Double? = null,
    @ColumnInfo(name = "popularity_rank") val popularityRank: Int? = null,
    @ColumnInfo(name = "remote_cover_ref") val remoteCoverRef: String? = null,
    @ColumnInfo(name = "local_cover_path") val localCoverPath: String? = null,
    @ColumnInfo(name = "cover_resolution_state", defaultValue = "'UNKNOWN'")
    val coverResolutionState: String = "UNKNOWN"
)

/**
 * Pack-defined searchable/filterable item fields (genre, author, platform, etc.).
 * The manifest defines field_id, its type, localized label and allowed values.
 * position makes multi-value fields possible; zero is used for single-value fields.
 * Exactly one value column should be populated, enforced by pack validation.
 * Dates can be stored as sortable ISO-8601 strings when declared by the manifest.
 */
@Entity(
    tableName = "media_item_attribute",
    primaryKeys = ["pack_id", "media_id", "field_id", "position"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["pack_id", "id"],
            childColumns = ["pack_id", "media_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["pack_id", "field_id", "value_text"], name = "idx_item_attr_text"),
        Index(value = ["pack_id", "field_id", "value_number"], name = "idx_item_attr_number")
    ]
)
data class MediaItemAttributeEntity(
    @ColumnInfo(name = "pack_id") val packId: Long,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "field_id") val fieldId: String,
    val position: Int = 0,
    @ColumnInfo(name = "value_text") val valueText: String? = null,
    @ColumnInfo(name = "value_number") val valueNumber: Double? = null,
    @ColumnInfo(name = "value_boolean") val valueBoolean: Boolean? = null
)

/** Alternative titles, useful for search in any language or catalog. */
@Entity(
    tableName = "media_alias",
    primaryKeys = ["pack_id", "media_id", "title"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["pack_id", "id"],
            childColumns = ["pack_id", "media_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["pack_id", "title"], name = "idx_media_alias_pack_title")]
)
data class MediaAliasEntity(
    @ColumnInfo(name = "pack_id") val packId: Long,
    @ColumnInfo(name = "media_id") val mediaId: String,
    val title: String,
    val language: String? = null,
    val kind: String? = null
)

/**
 * A pack-defined collection/group. groupType is declared by the pack manifest.
 * Its optional representative is a separate relation; its member count is
 * calculated from media_group_member. Neither can become stale in this row.
 */
@Entity(
    tableName = "media_group",
    primaryKeys = ["pack_id", "id"],
    indices = [
        Index(value = ["pack_id", "group_type"], name = "idx_media_group_pack_type"),
        Index(value = ["pack_id", "cover_resolution_state"], name = "idx_media_group_pack_cover_state"),
        Index(
            value = ["pack_id", "group_type", "group_popularity_rank"],
            name = "idx_media_group_pack_popularity_rank"
        ),
        Index(
            value = ["pack_id", "group_type", "provider", "provider_group_id"],
            name = "idx_media_group_pack_provider_identity",
            unique = true
        )
    ]
)
data class MediaGroupEntity(
    @ColumnInfo(name = "pack_id") val packId: Long,
    val id: String,
    @ColumnInfo(name = "group_type") val groupType: String,
    @ColumnInfo(name = "display_title") val displayTitle: String,
    val provider: String? = null,
    @ColumnInfo(name = "provider_group_id") val providerGroupId: String? = null,
    @ColumnInfo(name = "group_popularity") val popularity: Double? = null,
    @ColumnInfo(name = "group_popularity_rank") val popularityRank: Int? = null,
    @ColumnInfo(name = "remote_cover_ref") val remoteCoverRef: String? = null,
    @ColumnInfo(name = "local_cover_path") val localCoverPath: String? = null,
    @ColumnInfo(name = "cover_resolution_state", defaultValue = "'UNKNOWN'")
    val coverResolutionState: String = "UNKNOWN"
)

/** Optional pack-defined fields for group search, sorting and filtering. */
@Entity(
    tableName = "media_group_attribute",
    primaryKeys = ["pack_id", "group_id", "field_id", "position"],
    foreignKeys = [
        ForeignKey(
            entity = MediaGroupEntity::class,
            parentColumns = ["pack_id", "id"],
            childColumns = ["pack_id", "group_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["pack_id", "field_id", "value_text"], name = "idx_group_attr_text"),
        Index(value = ["pack_id", "field_id", "value_number"], name = "idx_group_attr_number")
    ]
)
data class MediaGroupAttributeEntity(
    @ColumnInfo(name = "pack_id") val packId: Long,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "field_id") val fieldId: String,
    val position: Int = 0,
    @ColumnInfo(name = "value_text") val valueText: String? = null,
    @ColumnInfo(name = "value_number") val valueNumber: Double? = null,
    @ColumnInfo(name = "value_boolean") val valueBoolean: Boolean? = null
)

/** Membership cannot cross pack boundaries. */
@Entity(
    tableName = "media_group_member",
    primaryKeys = ["pack_id", "group_id", "media_id"],
    foreignKeys = [
        ForeignKey(
            entity = MediaGroupEntity::class,
            parentColumns = ["pack_id", "id"],
            childColumns = ["pack_id", "group_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["pack_id", "id"],
            childColumns = ["pack_id", "media_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["pack_id", "media_id"], name = "idx_group_member_pack_media")]
)
data class MediaGroupMemberEntity(
    @ColumnInfo(name = "pack_id") val packId: Long,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    val position: Int
)

/**
 * Optional representative item for a group (zero or one per group).
 *
 * Keeping this as its own relation instead of a nullable column in media_group
 * lets SQLite delete it automatically when either the group or the item is
 * removed, without nullifying the non-null pack_id of a composite foreign key.
 * Both foreign keys include pack_id, so cross-pack references are impossible.
 *
 * The representative need not be a group member: pack-defined group covers
 * and curated selections may legitimately use any item from the same pack.
 * Insert/update this relation after both the group and item are installed.
 */
@Entity(
    tableName = "media_group_representative",
    primaryKeys = ["pack_id", "group_id"],
    foreignKeys = [
        ForeignKey(
            entity = MediaGroupEntity::class,
            parentColumns = ["pack_id", "id"],
            childColumns = ["pack_id", "group_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["pack_id", "id"],
            childColumns = ["pack_id", "media_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["pack_id", "media_id"], name = "idx_group_rep_pack_media")
    ]
)
data class MediaGroupRepresentativeEntity(
    @ColumnInfo(name = "pack_id") val packId: Long,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "media_id") val mediaId: String
)

/**
 * User data has NO foreign key to installed catalog records.
 * Removing a pack must never delete personal ratings or notes.
 */
@Entity(
    tableName = "media_rating",
    primaryKeys = ["pack_id", "media_id"],
    indices = [
        Index(value = ["pack_id", "final_score"], name = "idx_media_rating_pack_final"),
        Index(value = ["pack_id", "updated_at"], name = "idx_media_rating_pack_updated")
    ]
)
data class MediaRatingEntity(
    @ColumnInfo(name = "pack_id") val packId: Long,
    @ColumnInfo(name = "media_id") val mediaId: String,
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
    primaryKeys = ["pack_id", "media_id", "category_id"],
    foreignKeys = [
        ForeignKey(
            entity = MediaRatingEntity::class,
            parentColumns = ["pack_id", "media_id"],
            childColumns = ["pack_id", "media_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["pack_id", "category_id"], name = "idx_media_rating_score_pack_category")]
)
data class MediaRatingScoreEntity(
    @ColumnInfo(name = "pack_id") val packId: Long,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    val score: Double?,
    val applicable: Boolean
)

/** Personal group ratings also survive catalog and group removal. */
@Entity(
    tableName = "media_group_rating",
    primaryKeys = ["pack_id", "group_id"],
    indices = [
        Index(value = ["pack_id", "final_score"], name = "idx_group_rating_pack_final"),
        Index(value = ["pack_id", "updated_at"], name = "idx_group_rating_pack_updated")
    ]
)
data class MediaGroupRatingEntity(
    @ColumnInfo(name = "pack_id") val packId: Long,
    @ColumnInfo(name = "group_id") val groupId: String,
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
    primaryKeys = ["pack_id", "group_id", "category_id"],
    foreignKeys = [
        ForeignKey(
            entity = MediaGroupRatingEntity::class,
            parentColumns = ["pack_id", "group_id"],
            childColumns = ["pack_id", "group_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["pack_id", "category_id"], name = "idx_group_rating_score_pack_category")]
)
data class MediaGroupRatingScoreEntity(
    @ColumnInfo(name = "pack_id") val packId: Long,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    val score: Double?,
    val applicable: Boolean
)

@Entity(tableName = "media_catalog_metadata", primaryKeys = ["pack_id", "metadata_key"])
data class MediaCatalogMetadataEntity(
    @ColumnInfo(name = "pack_id") val packId: Long,
    @ColumnInfo(name = "metadata_key") val metadataKey: String,
    @ColumnInfo(name = "metadata_value") val metadataValue: String
)
