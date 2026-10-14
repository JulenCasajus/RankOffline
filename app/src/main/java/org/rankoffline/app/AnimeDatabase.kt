package org.rankoffline.app

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "anime",
    indices = [
        Index(value = ["title"], name = "idx_anime_title"),
        Index(value = ["popularity_rank"], name = "idx_anime_popularity_rank", unique = true),
        Index(value = ["format"], name = "idx_anime_format"),
        Index(value = ["season_year"], name = "idx_anime_season_year"),
        Index(value = ["media_status"], name = "idx_anime_media_status"),
        Index(value = ["source"], name = "idx_anime_source"),
        Index(value = ["episodes"], name = "idx_anime_episodes")
    ]
)
data class AnimeEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "mal_id") val malId: Long?,
    @ColumnInfo(name = "anilist_id") val anilistId: Long?,
    val popularity: Int?,
    @ColumnInfo(name = "popularity_rank") val popularityRank: Int?,
    @ColumnInfo(name = "poster_url") val posterUrl: String?,
    @ColumnInfo(name = "remote_cover_url") val remoteCoverUrl: String?,
    @ColumnInfo(name = "local_cover_path") val localCoverPath: String?,
    @ColumnInfo(name = "cover_resolution_state", defaultValue = "'UNKNOWN'") val coverResolutionState: String,
    val format: String? = null,
    @ColumnInfo(name = "season_year") val seasonYear: Int? = null,
    @ColumnInfo(name = "media_status") val mediaStatus: String? = null,
    val source: String? = null,
    val episodes: Int? = null,
    @ColumnInfo(name = "country_of_origin") val countryOfOrigin: String? = null,
    @ColumnInfo(name = "average_score") val averageScore: Int? = null,
    @ColumnInfo(name = "start_date") val startDate: String? = null
)

@Entity(
    tableName = "anime_group",
    indices = [Index(value = ["type"], name = "idx_anime_group_type"), Index(value = ["root_anime_id"], name = "idx_anime_group_root")],
    foreignKeys = [ForeignKey(entity = AnimeEntity::class, parentColumns = ["id"], childColumns = ["root_anime_id"])]
)
data class AnimeGroupEntity(
    @PrimaryKey val id: String,
    val type: String,
    @ColumnInfo(name = "root_anime_id") val rootAnimeId: String,
    @ColumnInfo(name = "display_title") val displayTitle: String
)

@Entity(
    tableName = "anime_group_member",
    primaryKeys = ["group_id", "anime_id"],
    indices = [Index(value = ["anime_id"], name = "idx_anime_group_member_anime")],
    foreignKeys = [
        ForeignKey(entity = AnimeGroupEntity::class, parentColumns = ["id"], childColumns = ["group_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AnimeEntity::class, parentColumns = ["id"], childColumns = ["anime_id"], onDelete = ForeignKey.CASCADE)
    ]
)
data class AnimeGroupMemberEntity(
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "anime_id") val animeId: String,
    val position: Int
)

@Entity(
    tableName = "group_rating",
    foreignKeys = [ForeignKey(entity = AnimeGroupEntity::class, parentColumns = ["id"], childColumns = ["group_id"], onDelete = ForeignKey.CASCADE)]
)
data class GroupRatingEntity(
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
    tableName = "group_rating_score",
    primaryKeys = ["group_id", "category_id"],
    foreignKeys = [ForeignKey(entity = GroupRatingEntity::class, parentColumns = ["group_id"], childColumns = ["group_id"], onDelete = ForeignKey.CASCADE)]
)
data class GroupRatingScoreEntity(
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    val score: Double?,
    val applicable: Boolean
)

@Entity(
    tableName = "anime_genre",
    primaryKeys = ["anime_id", "genre"],
    indices = [Index(value = ["genre"], name = "idx_anime_genre_genre")],
    foreignKeys = [ForeignKey(
        entity = AnimeEntity::class,
        parentColumns = ["id"],
        childColumns = ["anime_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class AnimeGenreEntity(
    @ColumnInfo(name = "anime_id") val animeId: String,
    val genre: String
)

@Entity(
    tableName = "anime_studio",
    primaryKeys = ["anime_id", "studio"],
    indices = [Index(value = ["studio"], name = "idx_anime_studio_studio")],
    foreignKeys = [ForeignKey(
        entity = AnimeEntity::class,
        parentColumns = ["id"],
        childColumns = ["anime_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class AnimeStudioEntity(
    @ColumnInfo(name = "anime_id") val animeId: String,
    val studio: String
)

@Entity(
    tableName = "rating",
    foreignKeys = [ForeignKey(
        entity = AnimeEntity::class,
        parentColumns = ["id"],
        childColumns = ["anime_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class RatingEntity(
    @PrimaryKey @ColumnInfo(name = "anime_id") val animeId: String,
    @ColumnInfo(name = "additive_score") val additiveScore: Double,
    @ColumnInfo(name = "quality_score") val qualityScore: Double?,
    @ColumnInfo(name = "final_score") val finalScore: Double?,
    val notes: String,
    val status: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "profile_id") val profileId: String? = null
)

@Entity(
    tableName = "rating_score",
    primaryKeys = ["anime_id", "category_id"],
    foreignKeys = [ForeignKey(
        entity = RatingEntity::class,
        parentColumns = ["anime_id"],
        childColumns = ["anime_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class RatingScoreEntity(
    @ColumnInfo(name = "anime_id") val animeId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    val score: Double?,
    val applicable: Boolean
)

@Entity(tableName = "catalog_metadata")
data class CatalogMetadataEntity(
    @PrimaryKey @ColumnInfo(name = "metadata_key") val key: String,
    @ColumnInfo(name = "metadata_value") val value: String
)

data class AnimeRow(
    val id: String,
    val title: String,
    val malId: Long?,
    val anilistId: Long?,
    val popularity: Int?,
    val popularityRank: Int?,
    val finalScore: Double?,
    val qualityScore: Double?,
    val additiveScore: Double?,
    val categoryScore: Double?,
    val status: String?,
    val remoteCoverUrl: String?,
    val localCoverPath: String?,
    val coverResolutionState: String,
    val format: String?,
    val seasonYear: Int?,
    val startDate: String?,
    val studio: String?,
    val groupId: String?,
    val groupType: String?,
    val rootAnimeId: String?,
    val memberCount: Int,
    val ratedCount: Int,
    val derived: Boolean
)

@Dao
interface AnimeDao {
    @Query(
        """
        SELECT a.id, a.title, a.mal_id AS malId, a.anilist_id AS anilistId, a.popularity,
               a.popularity_rank AS popularityRank,
               r.final_score AS finalScore, r.quality_score AS qualityScore,
               r.additive_score AS additiveScore, NULL AS categoryScore, r.status,
               COALESCE(a.remote_cover_url, a.poster_url) AS remoteCoverUrl,
               a.local_cover_path AS localCoverPath,
               a.cover_resolution_state AS coverResolutionState,
               a.format,a.season_year AS seasonYear,a.start_date AS startDate,
               (SELECT ast.studio FROM anime_studio ast WHERE ast.anime_id=a.id ORDER BY ast.studio COLLATE NOCASE LIMIT 1) AS studio,
               NULL AS groupId, NULL AS groupType, NULL AS rootAnimeId,
               0 AS memberCount, 0 AS ratedCount, 0 AS derived
        FROM anime a LEFT JOIN rating r ON a.id = r.anime_id
        WHERE (:query = '' OR a.title LIKE '%' || :query || '%' COLLATE NOCASE)
          AND (:genre IS NULL OR EXISTS (SELECT 1 FROM anime_genre ag WHERE ag.anime_id=a.id AND ag.genre=:genre))
          AND (:format IS NULL
               OR (:format='SERIES' AND a.format IN ('TV','TV_SHORT','ONA'))
               OR (:format='MOVIES' AND a.format='MOVIE')
               OR (:format='SPECIAL' AND a.format IN ('OVA','SPECIAL'))
               OR (:format='MUSIC' AND a.format='MUSIC'))
          AND (:year IS NULL OR a.season_year=:year)
          AND (:studio IS NULL OR EXISTS (SELECT 1 FROM anime_studio ast WHERE ast.anime_id=a.id AND ast.studio=:studio))
        ORDER BY
            CASE WHEN :sortOrder IN ('POPULARITY','POPULARITY_REVERSE') AND a.popularity_rank IS NULL THEN 1
                 ELSE 0 END ASC,
            CASE WHEN :sortOrder = 'POPULARITY' THEN a.popularity_rank END ASC,
            CASE WHEN :sortOrder = 'POPULARITY_REVERSE' THEN a.popularity_rank END DESC,
            CASE WHEN :sortOrder = 'TITLE_DESC' THEN a.title END COLLATE NOCASE DESC,
            a.title COLLATE NOCASE ASC
        LIMIT :limit
        """
    )
    suspend fun search(
        query: String,
        limit: Int,
        sortOrder: String,
        genre: String?,
        format: String?,
        year: Int?,
        studio: String?
    ): List<AnimeRow>

    suspend fun search(query: String, limit: Int, sortOrder: String): List<AnimeRow> =
        search(query, limit, sortOrder, null, null, null, null)

    @Query(
        """
        WITH visible AS (
            SELECT a.id, a.title, a.mal_id AS malId, a.anilist_id AS anilistId, a.popularity,
                   a.popularity_rank AS popularityRank,
                   r.final_score AS finalScore, r.quality_score AS qualityScore,
                   r.additive_score AS additiveScore, selected_score.score AS categoryScore, r.status,
                   COALESCE(a.remote_cover_url, a.poster_url) AS remoteCoverUrl,
                   a.local_cover_path AS localCoverPath,
                   a.cover_resolution_state AS coverResolutionState,
                   a.format,a.season_year AS seasonYear,a.start_date AS startDate,
                   (SELECT ast.studio FROM anime_studio ast WHERE ast.anime_id=a.id ORDER BY ast.studio COLLATE NOCASE LIMIT 1) AS studio,
                   NULL AS groupId, NULL AS groupType, NULL AS rootAnimeId,
                   0 AS memberCount, 0 AS ratedCount, 0 AS derived
            FROM anime a INNER JOIN rating r ON a.id = r.anime_id
            LEFT JOIN rating_score selected_score
              ON selected_score.anime_id=r.anime_id
             AND selected_score.category_id=:categoryId
             AND selected_score.applicable=1
            WHERE r.final_score IS NOT NULL
              AND (:genre IS NULL OR EXISTS (SELECT 1 FROM anime_genre ag WHERE ag.anime_id=a.id AND ag.genre=:genre))
              AND (:format IS NULL
                   OR (:format='SERIES' AND a.format IN ('TV','TV_SHORT','ONA'))
                   OR (:format='MOVIES' AND a.format='MOVIE')
                   OR (:format='SPECIAL' AND a.format IN ('OVA','SPECIAL'))
                   OR (:format='MUSIC' AND a.format='MUSIC'))
              AND (:year IS NULL OR a.season_year=:year)
              AND (:studio IS NULL OR EXISTS (SELECT 1 FROM anime_studio ast WHERE ast.anime_id=a.id AND ast.studio=:studio))

            UNION ALL

            SELECT g.id, g.display_title, a.mal_id AS malId, a.anilist_id AS anilistId,
                   a.popularity, a.popularity_rank AS popularityRank,
                   gr.final_score AS finalScore, gr.quality_score AS qualityScore,
                   gr.additive_score AS additiveScore, group_score.score AS categoryScore, gr.status,
                   COALESCE(a.remote_cover_url, a.poster_url) AS remoteCoverUrl,
                   a.local_cover_path AS localCoverPath,
                   a.cover_resolution_state AS coverResolutionState,
                   g.type AS format,a.season_year AS seasonYear,a.start_date AS startDate,
                   (SELECT ast.studio FROM anime_studio ast WHERE ast.anime_id=a.id ORDER BY ast.studio COLLATE NOCASE LIMIT 1) AS studio,
                   g.id AS groupId,g.type AS groupType,g.root_anime_id AS rootAnimeId,
                   (SELECT COUNT(*) FROM anime_group_member count_member WHERE count_member.group_id=g.id) AS memberCount,
                   0 AS ratedCount,0 AS derived
            FROM anime_group g
            JOIN anime a ON a.id=g.root_anime_id
            JOIN group_rating gr ON gr.group_id=g.id AND gr.final_score IS NOT NULL
            LEFT JOIN group_rating_score group_score
              ON group_score.group_id=g.id
             AND group_score.category_id=:categoryId
             AND group_score.applicable=1
            WHERE NOT EXISTS (
                SELECT 1
                FROM anime_group_member rated_member
                JOIN rating member_rating ON member_rating.anime_id=rated_member.anime_id
                WHERE rated_member.group_id=g.id AND member_rating.final_score IS NOT NULL
            )
              AND (:genre IS NULL OR EXISTS (
                  SELECT 1 FROM anime_group_member genre_member
                  JOIN anime_genre ag ON ag.anime_id=genre_member.anime_id
                  WHERE genre_member.group_id=g.id AND ag.genre=:genre
              ))
              AND (:format IS NULL OR g.type=:format)
              AND (:year IS NULL OR EXISTS (
                  SELECT 1 FROM anime_group_member year_member
                  JOIN anime year_anime ON year_anime.id=year_member.anime_id
                  WHERE year_member.group_id=g.id AND year_anime.season_year=:year
              ))
              AND (:studio IS NULL OR EXISTS (
                  SELECT 1 FROM anime_group_member studio_member
                  JOIN anime_studio ast ON ast.anime_id=studio_member.anime_id
                  WHERE studio_member.group_id=g.id AND ast.studio=:studio
              ))
        )
        SELECT * FROM visible
        ORDER BY
          CASE WHEN
            CASE :sortOrder
              WHEN 'QUALITY_SCORE' THEN qualityScore
              WHEN 'PERSONAL_TASTE' THEN additiveScore
              WHEN 'CATEGORY_SCORE' THEN categoryScore
              ELSE finalScore
            END IS NULL THEN 1 ELSE 0 END ASC,
          CASE WHEN :descending THEN
            CASE :sortOrder
              WHEN 'QUALITY_SCORE' THEN qualityScore
              WHEN 'PERSONAL_TASTE' THEN additiveScore
              WHEN 'CATEGORY_SCORE' THEN categoryScore
              ELSE finalScore
            END
          END DESC,
          CASE WHEN NOT :descending THEN
            CASE :sortOrder
              WHEN 'QUALITY_SCORE' THEN qualityScore
              WHEN 'PERSONAL_TASTE' THEN additiveScore
              WHEN 'CATEGORY_SCORE' THEN categoryScore
              ELSE finalScore
            END
          END ASC,
          title COLLATE NOCASE
        LIMIT :limit
        """
    )
    fun observeRanking(
        limit: Int,
        sortOrder: String,
        descending: Boolean,
        categoryId: String,
        genre: String?,
        format: String?,
        year: Int?,
        studio: String?
    ): Flow<List<AnimeRow>>

    fun observeRanking(limit: Int): Flow<List<AnimeRow>> =
        observeRanking(limit, RankingSortOrder.FINAL_SCORE.name, true, "", null, null, null, null)

    @RawQuery
    suspend fun groupedSearch(query: SupportSQLiteQuery): List<AnimeRow>

    @RawQuery(observedEntities = [
        AnimeEntity::class, AnimeGenreEntity::class, AnimeStudioEntity::class,
        AnimeGroupEntity::class, AnimeGroupMemberEntity::class,
        RatingEntity::class, RatingScoreEntity::class,
        GroupRatingEntity::class, GroupRatingScoreEntity::class
    ])
    fun observeGroupedRanking(query: SupportSQLiteQuery): Flow<List<AnimeRow>>

    @Query(
        """
        SELECT a.id,a.title,a.mal_id AS malId,a.anilist_id AS anilistId,a.popularity,
               a.popularity_rank AS popularityRank,r.final_score AS finalScore,
               r.quality_score AS qualityScore,r.additive_score AS additiveScore,
               NULL AS categoryScore,r.status,
               COALESCE(a.remote_cover_url,a.poster_url) AS remoteCoverUrl,
               a.local_cover_path AS localCoverPath,a.cover_resolution_state AS coverResolutionState,
               a.format,a.season_year AS seasonYear,a.start_date AS startDate,
               (SELECT ast.studio FROM anime_studio ast WHERE ast.anime_id=a.id ORDER BY ast.studio COLLATE NOCASE LIMIT 1) AS studio,
               NULL AS groupId,NULL AS groupType,NULL AS rootAnimeId,
               0 AS memberCount,0 AS ratedCount,0 AS derived
        FROM anime_group_member gm
        JOIN anime a ON a.id=gm.anime_id
        LEFT JOIN rating r ON r.anime_id=a.id
        WHERE gm.group_id=:groupId
        ORDER BY gm.position
        """
    )
    suspend fun groupMembers(groupId: String): List<AnimeRow>

    @Query("SELECT * FROM anime_group WHERE id=:groupId")
    suspend fun group(groupId: String): AnimeGroupEntity?

    @Query("SELECT * FROM group_rating WHERE group_id=:groupId")
    suspend fun groupRating(groupId: String): GroupRatingEntity?

    @Query("SELECT * FROM group_rating_score WHERE group_id=:groupId")
    suspend fun groupRatingScores(groupId: String): List<GroupRatingScoreEntity>

    @Query("SELECT r.* FROM anime_group_member gm JOIN rating r ON r.anime_id=gm.anime_id WHERE gm.group_id=:groupId AND r.final_score IS NOT NULL")
    suspend fun ratedGroupMembers(groupId: String): List<RatingEntity>

    @Query("SELECT rs.* FROM anime_group_member gm JOIN rating r ON r.anime_id=gm.anime_id JOIN rating_score rs ON rs.anime_id=r.anime_id WHERE gm.group_id=:groupId AND r.final_score IS NOT NULL")
    suspend fun ratedGroupMemberScores(groupId: String): List<RatingScoreEntity>

    @Upsert
    suspend fun upsertGroupRating(rating: GroupRatingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupRatingScores(scores: List<GroupRatingScoreEntity>)

    @Transaction
    suspend fun saveGroupRating(rating: GroupRatingEntity, scores: List<GroupRatingScoreEntity>) {
        upsertGroupRating(rating)
        if (scores.isNotEmpty()) upsertGroupRatingScores(scores)
    }

    @Query("DELETE FROM group_rating WHERE group_id=:groupId")
    suspend fun deleteGroupRating(groupId: String)

    @Query("DELETE FROM group_rating_score WHERE category_id=:categoryId")
    suspend fun deleteGroupRatingScoresForCategory(categoryId: String)

    @Query("DELETE FROM group_rating_score WHERE category_id NOT IN (:categoryIds)")
    suspend fun deleteGroupRatingScoresOutsideCategories(categoryIds: List<String>)

    @Query("DELETE FROM group_rating_score")
    suspend fun deleteAllGroupRatingScores()

    @Query("SELECT DISTINCT genre FROM anime_genre ORDER BY genre COLLATE NOCASE")
    suspend fun genres(): List<String>

    @Query("SELECT DISTINCT format FROM anime WHERE format IS NOT NULL AND TRIM(format) != '' AND LOWER(TRIM(format)) != 'null' ORDER BY format COLLATE NOCASE")
    suspend fun formats(): List<String>

    @Query("SELECT DISTINCT season_year FROM anime WHERE season_year IS NOT NULL ORDER BY season_year DESC")
    suspend fun years(): List<Int>

    @Query("SELECT DISTINCT studio FROM anime_studio ORDER BY studio COLLATE NOCASE")
    suspend fun studios(): List<String>

    @Query("SELECT COUNT(*) FROM anime")
    suspend fun animeCount(): Int

    @Query("SELECT COUNT(*) FROM anime WHERE local_cover_path IS NOT NULL")
    fun observeDownloadedCoverCount(): Flow<Int>

    @Query("SELECT metadata_value FROM catalog_metadata WHERE metadata_key = :key")
    suspend fun metadata(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMetadata(metadata: CatalogMetadataEntity)

    @Query("SELECT * FROM rating WHERE anime_id = :animeId")
    suspend fun rating(animeId: String): RatingEntity?

    @Query("SELECT * FROM rating_score WHERE anime_id = :animeId")
    suspend fun ratingScores(animeId: String): List<RatingScoreEntity>

    @Query("DELETE FROM rating_score WHERE category_id = :categoryId")
    suspend fun deleteRatingScoresForCategory(categoryId: String)

    @Query("DELETE FROM rating_score WHERE category_id NOT IN (:categoryIds)")
    suspend fun deleteRatingScoresOutsideCategories(categoryIds: List<String>)

    @Query("DELETE FROM rating_score")
    suspend fun deleteAllRatingScores()

    @Upsert
    suspend fun upsertRating(rating: RatingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRatingScores(scores: List<RatingScoreEntity>)

    @Transaction
    suspend fun saveRating(rating: RatingEntity, scores: List<RatingScoreEntity>) {
        upsertRating(rating)
        if (scores.isNotEmpty()) upsertRatingScores(scores)
    }

    @Query("DELETE FROM rating WHERE anime_id = :animeId")
    suspend fun deleteRating(animeId: String)

    @Query("SELECT id FROM anime WHERE anilist_id = :anilistId")
    suspend fun animeIdsByAnilistId(anilistId: Long): List<String>

    @Query("SELECT id FROM anime WHERE mal_id = :malId")
    suspend fun animeIdsByMalId(malId: Long): List<String>

    @Query("SELECT * FROM anime WHERE anilist_id IN (:anilistIds)")
    suspend fun animeByAnilistIds(anilistIds: List<Long>): List<AnimeEntity>

    @Query("SELECT * FROM anime WHERE mal_id IN (:malIds)")
    suspend fun animeByMalIds(malIds: List<Long>): List<AnimeEntity>

    @Query("UPDATE anime SET remote_cover_url = :url, cover_resolution_state = :state WHERE id = :animeId")
    suspend fun updateRemoteCover(animeId: String, url: String?, state: String)

    @Query("UPDATE anime SET local_cover_path = :path, cover_resolution_state = :state WHERE id = :animeId")
    suspend fun updateLocalCover(animeId: String, path: String?, state: String)

    @Query(
        """
        UPDATE anime
        SET local_cover_path = NULL,
            cover_resolution_state = CASE
                WHEN remote_cover_url IS NOT NULL OR poster_url IS NOT NULL THEN 'AVAILABLE'
                ELSE 'UNKNOWN'
            END
        WHERE id = :animeId
        """
    )
    suspend fun clearLocalCoverPath(animeId: String)

    @Query("UPDATE anime SET cover_resolution_state = :state WHERE id = :animeId")
    suspend fun updateCoverResolutionState(animeId: String, state: String)

    @Query("SELECT local_cover_path FROM anime WHERE id = :animeId")
    fun observeLocalCoverPath(animeId: String): Flow<String?>

    @Query("SELECT local_cover_path FROM anime WHERE local_cover_path IS NOT NULL")
    suspend fun allLocalCoverPaths(): List<String>

    @Query(
        """
        UPDATE anime
        SET local_cover_path = NULL,
            cover_resolution_state = CASE
                WHEN remote_cover_url IS NOT NULL OR poster_url IS NOT NULL THEN 'AVAILABLE'
                ELSE 'UNKNOWN'
            END
        WHERE local_cover_path IS NOT NULL
        """
    )
    suspend fun clearAllLocalCoverPaths()
}

@Database(
    entities = [
        AnimeEntity::class,
        AnimeGenreEntity::class,
        AnimeStudioEntity::class,
        AnimeGroupEntity::class,
        AnimeGroupMemberEntity::class,
        RatingEntity::class,
        RatingScoreEntity::class,
        GroupRatingEntity::class,
        GroupRatingScoreEntity::class,
        CatalogMetadataEntity::class,
        MediaItemEntity::class,
        MediaGenreEntity::class,
        MediaCreditEntity::class,
        MediaAliasEntity::class,
        MediaGroupEntity::class,
        MediaGroupMemberEntity::class,
        MediaRatingEntity::class,
        MediaRatingScoreEntity::class,
        MediaGroupRatingEntity::class,
        MediaGroupRatingScoreEntity::class,
        MediaCatalogMetadataEntity::class
    ],
    version = 11,
    exportSchema = true
)
abstract class AnimeDatabase : RoomDatabase() {
    abstract fun animeDao(): AnimeDao
    abstract fun mediaDao(): MediaDao
    abstract fun mediaGroupDao(): MediaGroupDao

    companion object {
        const val DATABASE_NAME = "anime_rank.db"

        @Volatile private var instance: AnimeDatabase? = null

        fun getInstance(context: Context): AnimeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AnimeDatabase::class.java, DATABASE_NAME)
                .addMigrations(
                    MIGRATION_1_5,
                    MIGRATION_2_5,
                    MIGRATION_3_5,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11
                )
                .build()
                .also { instance = it }
        }

        val MIGRATION_1_5 = migrationTo5(1)
        val MIGRATION_2_5 = migrationTo5(2)
        val MIGRATION_3_5 = migrationTo5(3)
        val MIGRATION_4_5 = migrationTo5(4)
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE anime ADD COLUMN popularity INTEGER")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE anime ADD COLUMN popularity_rank INTEGER")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_anime_popularity_rank " +
                        "ON anime(popularity_rank)"
                )
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rating ADD COLUMN profile_id TEXT")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE anime ADD COLUMN format TEXT")
                db.execSQL("ALTER TABLE anime ADD COLUMN season_year INTEGER")
                db.execSQL("ALTER TABLE anime ADD COLUMN media_status TEXT")
                db.execSQL("ALTER TABLE anime ADD COLUMN source TEXT")
                db.execSQL("ALTER TABLE anime ADD COLUMN episodes INTEGER")
                db.execSQL("ALTER TABLE anime ADD COLUMN country_of_origin TEXT")
                db.execSQL("ALTER TABLE anime ADD COLUMN average_score INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_anime_format ON anime(format)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_anime_season_year ON anime(season_year)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_anime_media_status ON anime(media_status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_anime_source ON anime(source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_anime_episodes ON anime(episodes)")
                db.execSQL("CREATE TABLE IF NOT EXISTS anime_genre(anime_id TEXT NOT NULL,genre TEXT NOT NULL,PRIMARY KEY(anime_id,genre),FOREIGN KEY(anime_id) REFERENCES anime(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_anime_genre_genre ON anime_genre(genre)")
                db.execSQL("CREATE TABLE IF NOT EXISTS anime_studio(anime_id TEXT NOT NULL,studio TEXT NOT NULL,PRIMARY KEY(anime_id,studio),FOREIGN KEY(anime_id) REFERENCES anime(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_anime_studio_studio ON anime_studio(studio)")
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE anime ADD COLUMN start_date TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS anime_group(id TEXT NOT NULL PRIMARY KEY,type TEXT NOT NULL,root_anime_id TEXT NOT NULL,display_title TEXT NOT NULL,FOREIGN KEY(root_anime_id) REFERENCES anime(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_anime_group_type ON anime_group(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_anime_group_root ON anime_group(root_anime_id)")
                db.execSQL("CREATE TABLE IF NOT EXISTS anime_group_member(group_id TEXT NOT NULL,anime_id TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(group_id,anime_id),FOREIGN KEY(group_id) REFERENCES anime_group(id) ON UPDATE NO ACTION ON DELETE CASCADE,FOREIGN KEY(anime_id) REFERENCES anime(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_anime_group_member_anime ON anime_group_member(anime_id)")
                db.execSQL("CREATE TABLE IF NOT EXISTS group_rating(group_id TEXT NOT NULL PRIMARY KEY,additive_score REAL NOT NULL,quality_score REAL,final_score REAL,notes TEXT NOT NULL,status TEXT NOT NULL,updated_at INTEGER NOT NULL,profile_id TEXT,FOREIGN KEY(group_id) REFERENCES anime_group(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS group_rating_score(group_id TEXT NOT NULL,category_id TEXT NOT NULL,score REAL,applicable INTEGER NOT NULL,PRIMARY KEY(group_id,category_id),FOREIGN KEY(group_id) REFERENCES group_rating(group_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_item` (`id` TEXT NOT NULL, `media_type` TEXT NOT NULL, `provider` TEXT NOT NULL, `provider_id` TEXT NOT NULL, `title` TEXT NOT NULL, `original_title` TEXT, `year` INTEGER, `start_date` TEXT, `format` TEXT, `status` TEXT, `original_language` TEXT, `demographic` TEXT, `part_number` INTEGER, `popularity` REAL, `popularity_rank` INTEGER, `remote_cover_ref` TEXT, `local_cover_path` TEXT, `cover_resolution_state` TEXT NOT NULL DEFAULT 'UNKNOWN', PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_media_item_media_type` ON `media_item` (`media_type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_media_item_type_title` ON `media_item` (`media_type`, `title`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_media_item_type_popularity_rank` ON `media_item` (`media_type`, `popularity_rank`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_media_item_type_year` ON `media_item` (`media_type`, `year`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_media_item_type_format` ON `media_item` (`media_type`, `format`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_media_item_type_demographic` ON `media_item` (`media_type`, `demographic`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_media_item_provider_identity` ON `media_item` (`media_type`, `provider`, `provider_id`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_genre` (`media_id` TEXT NOT NULL, `genre` TEXT NOT NULL, PRIMARY KEY(`media_id`, `genre`), FOREIGN KEY(`media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_media_genre_genre` ON `media_genre` (`genre`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_credit` (`media_id` TEXT NOT NULL, `role` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`media_id`, `role`, `name`), FOREIGN KEY(`media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_media_credit_role_name` ON `media_credit` (`role`, `name`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_alias` (`media_id` TEXT NOT NULL, `title` TEXT NOT NULL, `language` TEXT, `kind` TEXT, PRIMARY KEY(`media_id`, `title`), FOREIGN KEY(`media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_media_alias_title` ON `media_alias` (`title`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_group` (`id` TEXT NOT NULL, `media_type` TEXT NOT NULL, `group_type` TEXT NOT NULL, `provider` TEXT, `provider_group_id` TEXT, `display_title` TEXT NOT NULL, `representative_media_id` TEXT, `remote_cover_ref` TEXT, `local_cover_path` TEXT, `cover_resolution_state` TEXT NOT NULL DEFAULT 'UNKNOWN', PRIMARY KEY(`id`), FOREIGN KEY(`representative_media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_media_group_type` ON `media_group` (`media_type`, `group_type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_media_group_representative` ON `media_group` (`representative_media_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_media_group_provider_identity` ON `media_group` (`media_type`, `group_type`, `provider`, `provider_group_id`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_group_member` (`group_id` TEXT NOT NULL, `media_id` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`group_id`, `media_id`), FOREIGN KEY(`group_id`) REFERENCES `media_group`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_media_group_member_media` ON `media_group_member` (`media_id`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_rating` (`media_id` TEXT NOT NULL, `additive_score` REAL NOT NULL, `quality_score` REAL, `final_score` REAL, `notes` TEXT NOT NULL, `status` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, `profile_id` TEXT, PRIMARY KEY(`media_id`), FOREIGN KEY(`media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_rating_score` (`media_id` TEXT NOT NULL, `category_id` TEXT NOT NULL, `score` REAL, `applicable` INTEGER NOT NULL, PRIMARY KEY(`media_id`, `category_id`), FOREIGN KEY(`media_id`) REFERENCES `media_rating`(`media_id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_group_rating` (`group_id` TEXT NOT NULL, `additive_score` REAL NOT NULL, `quality_score` REAL, `final_score` REAL, `notes` TEXT NOT NULL, `status` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, `profile_id` TEXT, PRIMARY KEY(`group_id`), FOREIGN KEY(`group_id`) REFERENCES `media_group`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_group_rating_score` (`group_id` TEXT NOT NULL, `category_id` TEXT NOT NULL, `score` REAL, `applicable` INTEGER NOT NULL, PRIMARY KEY(`group_id`, `category_id`), FOREIGN KEY(`group_id`) REFERENCES `media_group_rating`(`group_id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_catalog_metadata` (`media_type` TEXT NOT NULL, `metadata_key` TEXT NOT NULL, `metadata_value` TEXT NOT NULL, PRIMARY KEY(`media_type`, `metadata_key`))")
            }
        }

        private fun migrationTo5(from: Int) = object : Migration(from, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumn(db, "anime", "poster_url", "ALTER TABLE anime ADD COLUMN poster_url TEXT")
                ensureColumn(db, "anime", "remote_cover_url", "ALTER TABLE anime ADD COLUMN remote_cover_url TEXT")
                ensureColumn(db, "anime", "local_cover_path", "ALTER TABLE anime ADD COLUMN local_cover_path TEXT")
                ensureColumn(
                    db,
                    "anime",
                    "cover_resolution_state",
                    "ALTER TABLE anime ADD COLUMN cover_resolution_state TEXT NOT NULL DEFAULT 'UNKNOWN'"
                )
                db.execSQL(
                    "UPDATE anime SET remote_cover_url = poster_url " +
                        "WHERE remote_cover_url IS NULL AND poster_url IS NOT NULL"
                )
                db.execSQL(
                    "UPDATE anime SET cover_resolution_state = 'AVAILABLE' " +
                        "WHERE remote_cover_url IS NOT NULL OR local_cover_path IS NOT NULL"
                )

                // Copy first so rebuilding the parent anime table never depends on SQLite's
                // version-specific foreign-key rewrite behavior for ALTER TABLE RENAME.
                db.execSQL("CREATE TABLE rating_legacy AS SELECT * FROM rating")
                db.execSQL("DROP TABLE rating")
                db.execSQL("ALTER TABLE anime RENAME TO anime_legacy")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS anime(
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        mal_id INTEGER,
                        anilist_id INTEGER,
                        poster_url TEXT,
                        remote_cover_url TEXT,
                        local_cover_path TEXT,
                        cover_resolution_state TEXT NOT NULL DEFAULT 'UNKNOWN'
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO anime(
                        id,title,mal_id,anilist_id,poster_url,remote_cover_url,local_cover_path,cover_resolution_state
                    )
                    SELECT id,title,mal_id,anilist_id,poster_url,remote_cover_url,local_cover_path,cover_resolution_state
                    FROM anime_legacy
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rating(
                        anime_id TEXT NOT NULL PRIMARY KEY,
                        additive_score REAL NOT NULL,
                        quality_score REAL,
                        final_score REAL,
                        notes TEXT NOT NULL,
                        status TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        FOREIGN KEY(anime_id) REFERENCES anime(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO rating(anime_id, additive_score, quality_score, final_score, notes, status, updated_at)
                    SELECT anime_id, personal_taste, base_score, final_score, notes, status, updated_at
                    FROM rating_legacy
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rating_score(
                        anime_id TEXT NOT NULL,
                        category_id TEXT NOT NULL,
                        score REAL,
                        applicable INTEGER NOT NULL,
                        PRIMARY KEY(anime_id, category_id),
                        FOREIGN KEY(anime_id) REFERENCES rating(anime_id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                listOf("writing", "characters", "engagement", "visuals", "worldbuilding").forEach { categoryId ->
                    db.execSQL(
                        """
                        INSERT INTO rating_score(anime_id, category_id, score, applicable)
                        SELECT anime_id, '$categoryId', $categoryId,
                               CASE WHEN $categoryId IS NULL THEN 0 ELSE 1 END
                        FROM rating_legacy
                        """.trimIndent()
                    )
                }
                db.execSQL("DROP TABLE rating_legacy")
                db.execSQL("DROP TABLE anime_legacy")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS catalog_metadata(
                        metadata_key TEXT NOT NULL PRIMARY KEY,
                        metadata_value TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_anime_title ON anime(title)")
            }
        }

        private fun ensureColumn(db: SupportSQLiteDatabase, table: String, column: String, sql: String) {
            val exists = db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext() && !found) found = cursor.getString(nameIndex) == column
                found
            }
            if (!exists) db.execSQL(sql)
        }
    }
}
