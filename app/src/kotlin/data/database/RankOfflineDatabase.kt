package org.rankoffline.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** New universal schema, intentionally separate from the published legacy Anime database. */
@Database(
    entities = [
        MediaItemEntity::class,
        MediaItemAttributeEntity::class,
        MediaAliasEntity::class,
        MediaGroupEntity::class,
        MediaGroupAliasEntity::class,
        MediaGroupAttributeEntity::class,
        MediaGroupMemberEntity::class,
        MediaGroupRepresentativeEntity::class,
        MediaRatingEntity::class,
        MediaRatingScoreEntity::class,
        MediaGroupRatingEntity::class,
        MediaGroupRatingScoreEntity::class,
        MediaCatalogMetadataEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class RankOfflineDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun mediaGroupDao(): MediaGroupDao
    abstract fun mediaCatalogDao(): MediaCatalogDao
    abstract fun personalDataDao(): PersonalDataDao

    companion object {
        const val DATABASE_NAME = "rankoffline_universal.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `media_group_alias` (
                    `pack_id` INTEGER NOT NULL, `group_id` TEXT NOT NULL, `title` TEXT NOT NULL,
                    `language` TEXT, `kind` TEXT,
                    PRIMARY KEY(`pack_id`, `group_id`, `title`),
                    FOREIGN KEY(`pack_id`, `group_id`) REFERENCES `media_group`(`pack_id`, `id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
                )""".trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_media_group_alias_pack_title` " +
                    "ON `media_group_alias` (`pack_id`, `title`)")
            }
        }

        @Volatile private var instance: RankOfflineDatabase? = null

        fun getInstance(context: Context): RankOfflineDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                RankOfflineDatabase::class.java,
                DATABASE_NAME
            ).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                // No destructive fallback: migration/backup from the original app is a separate task.
                .build().also { instance = it }
        }
    }
}
