package org.rankoffline.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** New universal schema, intentionally separate from the published legacy Anime database. */
@Database(
    entities = [
        MediaItemEntity::class,
        MediaItemAttributeEntity::class,
        MediaAliasEntity::class,
        MediaGroupEntity::class,
        MediaGroupAttributeEntity::class,
        MediaGroupMemberEntity::class,
        MediaGroupRepresentativeEntity::class,
        MediaRatingEntity::class,
        MediaRatingScoreEntity::class,
        MediaGroupRatingEntity::class,
        MediaGroupRatingScoreEntity::class,
        MediaCatalogMetadataEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class RankOfflineDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun mediaGroupDao(): MediaGroupDao
    abstract fun mediaCatalogDao(): MediaCatalogDao
    abstract fun personalDataDao(): PersonalDataDao

    companion object {
        const val DATABASE_NAME = "rankoffline_universal.db"

        @Volatile private var instance: RankOfflineDatabase? = null

        fun getInstance(context: Context): RankOfflineDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                RankOfflineDatabase::class.java,
                DATABASE_NAME
            ).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // No destructive fallback: migration/backup from the original app is a separate task.
                .build().also { instance = it }
        }
    }
}
