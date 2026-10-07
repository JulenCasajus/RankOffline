package com.animerank.offline

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "anime",
    indices = [
        Index(value = ["title"], name = "idx_anime_title"),
        Index(value = ["popularity_rank"], name = "idx_anime_popularity_rank", unique = true)
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
    @ColumnInfo(name = "cover_resolution_state", defaultValue = "'UNKNOWN'") val coverResolutionState: String
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
    @ColumnInfo(name = "updated_at") val updatedAt: Long
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
    val status: String?,
    val remoteCoverUrl: String?,
    val localCoverPath: String?,
    val coverResolutionState: String
)

data class RatingWithScores(
    @Embedded val rating: RatingEntity,
    @Relation(parentColumn = "anime_id", entityColumn = "anime_id")
    val scores: List<RatingScoreEntity>
)

@Dao
interface AnimeDao {
    @Query(
        """
        SELECT a.id, a.title, a.mal_id AS malId, a.anilist_id AS anilistId, a.popularity,
               a.popularity_rank AS popularityRank,
               r.final_score AS finalScore, r.quality_score AS qualityScore,
               r.additive_score AS additiveScore, r.status,
               COALESCE(a.remote_cover_url, a.poster_url) AS remoteCoverUrl,
               a.local_cover_path AS localCoverPath,
               a.cover_resolution_state AS coverResolutionState
        FROM anime a LEFT JOIN rating r ON a.id = r.anime_id
        WHERE (:query = '' OR a.title LIKE '%' || :query || '%' COLLATE NOCASE)
        ORDER BY
            CASE WHEN :sortOrder = 'POPULARITY' AND a.popularity_rank IS NULL THEN 1
                 ELSE 0 END ASC,
            CASE WHEN :sortOrder = 'POPULARITY' THEN a.popularity_rank END ASC,
            CASE WHEN :sortOrder = 'TITLE_DESC' THEN a.title END COLLATE NOCASE DESC,
            a.title COLLATE NOCASE ASC
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int, sortOrder: String): List<AnimeRow>

    @Query(
        """
        SELECT a.id, a.title, a.mal_id AS malId, a.anilist_id AS anilistId, a.popularity,
               a.popularity_rank AS popularityRank,
               r.final_score AS finalScore, r.quality_score AS qualityScore,
               r.additive_score AS additiveScore, r.status,
               COALESCE(a.remote_cover_url, a.poster_url) AS remoteCoverUrl,
               a.local_cover_path AS localCoverPath,
               a.cover_resolution_state AS coverResolutionState
        FROM anime a INNER JOIN rating r ON a.id = r.anime_id
        WHERE r.final_score IS NOT NULL
        ORDER BY r.final_score DESC, a.title COLLATE NOCASE
        LIMIT :limit
        """
    )
    fun observeRanking(limit: Int): Flow<List<AnimeRow>>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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

    @Transaction
    @Query("SELECT * FROM rating")
    suspend fun allRatingsWithScores(): List<RatingWithScores>

    @Query("UPDATE rating SET quality_score = :qualityScore, final_score = :finalScore WHERE anime_id = :animeId")
    suspend fun updateDerivedScores(animeId: String, qualityScore: Double?, finalScore: Double?)

    @Transaction
    suspend fun recalculateAll(config: RatingSystemConfig) {
        allRatingsWithScores().forEach { entry ->
            val scores = entry.scores.associate { score ->
                score.categoryId to score.score.takeIf { score.applicable }
            }
            val result = ScoreCalculator.calculate(scores, entry.rating.additiveScore, config)
            updateDerivedScores(entry.rating.animeId, result.qualityScore, result.finalScore)
        }
    }

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
    entities = [AnimeEntity::class, RatingEntity::class, RatingScoreEntity::class, CatalogMetadataEntity::class],
    version = 7,
    exportSchema = true
)
abstract class AnimeDatabase : RoomDatabase() {
    abstract fun animeDao(): AnimeDao

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
                    MIGRATION_6_7
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
