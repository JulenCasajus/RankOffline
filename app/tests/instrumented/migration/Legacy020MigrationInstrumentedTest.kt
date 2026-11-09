package org.rankoffline.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Legacy020MigrationInstrumentedTest {
    private val context by lazy { ApplicationProvider.getApplicationContext<Context>() }

    @Test fun legacyAndGenericRatingsKeepNewerParentAndMatchingScores() = runBlocking {
        val source = File(context.cacheDir, "legacy-020-fixture.db")
        source.delete()
        val old = SQLiteDatabase.openOrCreateDatabase(source, null)
        try {
            createLegacyTables(old)
            old.execSQL("PRAGMA user_version=14")
            old.execSQL("INSERT INTO media_rating VALUES ('nautiljon:old+anime',2,7,9,'generic','done',100,'general','animes')")
            old.execSQL("INSERT INTO media_rating_score VALUES ('nautiljon:old+anime','writing',7,1,'animes')")
            old.execSQL("INSERT INTO rating VALUES ('nautiljon:old+anime',3,8,9.5,'legacy','done',200,'general')")
            old.execSQL("INSERT INTO rating_score VALUES ('nautiljon:old+anime','writing',8,1)")
            old.execSQL("INSERT INTO media_rating VALUES ('manga:42',1,6,7,'manga','reading',300,NULL,'mangas')")
            old.execSQL("INSERT INTO media_group_rating VALUES ('old+group',1,6,7,'group','done',300,NULL,'animes')")
            old.execSQL("INSERT INTO media_group_rating_score VALUES ('old+group','visuals',6,1,'animes')")
        } finally { old.close() }
        val database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java).build()
        try {
            val imported = Legacy020Reader(source).read()
            assertEquals(2, imported.ratings.size)
            assertEquals(1, imported.groupRatings.size)
            database.personalDataDao().restore(imported, replace = false)
            val ratings = database.personalDataDao().allRatings().associateBy { it.packId to it.mediaId }
            assertEquals("legacy", ratings[1L to "nautiljon:old-anime"]?.notes)
            assertEquals("manga", ratings[2L to "manga:42"]?.notes)
            assertEquals("old-group", database.personalDataDao().allGroupRatings().single().groupId)
            assertEquals(8.0, database.personalDataDao().allRatingScores().single().score!!, 0.0)
            assertEquals(6.0, database.personalDataDao().allGroupRatingScores().single().score!!, 0.0)
            // A newer v0.2.2 edit wins even when the one-time import is retried after a crash.
            database.personalDataDao().upsertRatings(listOf(
                ratings.getValue(1L to "nautiljon:old-anime").copy(notes = "newer", updatedAt = 400)
            ))
            database.personalDataDao().restore(imported, replace = false)
            assertEquals("newer", database.personalDataDao().allRatings()
                .single { it.packId == 1L }.notes)
            assertTrue(source.isFile)
        } finally { database.close(); source.delete() }
    }

    @Test fun unsupportedSourceSchemaCannotTouchNewRatings() = runBlocking {
        val source = File(context.cacheDir, "legacy-020-wrong-schema.db")
        source.delete()
        SQLiteDatabase.openOrCreateDatabase(source, null).use { it.execSQL("PRAGMA user_version=13") }
        val database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java).build()
        try {
            database.personalDataDao().upsertRatings(listOf(
                MediaRatingEntity(1, "keep", 1.0, 5.0, 6.0, "personal", "done", 1)
            ))
            assertTrue(runCatching { Legacy020Reader(source).read() }.isFailure)
            assertEquals("personal", database.personalDataDao().allRatings().single().notes)
            assertFalse(database.personalDataDao().allRatings().isEmpty())
        } finally { database.close(); source.delete() }
    }

    private fun createLegacyTables(db: SQLiteDatabase) {
        listOf("media_rating" to "media_id", "media_group_rating" to "group_id").forEach { (table, id) ->
            db.execSQL("CREATE TABLE $table ($id TEXT,additive_score REAL,quality_score REAL," +
                "final_score REAL,notes TEXT,status TEXT,updated_at INTEGER,profile_id TEXT,pack_id TEXT)")
            db.execSQL("CREATE TABLE ${table}_score ($id TEXT,category_id TEXT,score REAL," +
                "applicable INTEGER,pack_id TEXT)")
        }
        listOf("rating" to "anime_id", "group_rating" to "group_id").forEach { (table, id) ->
            db.execSQL("CREATE TABLE $table ($id TEXT,additive_score REAL,quality_score REAL," +
                "final_score REAL,notes TEXT,status TEXT,updated_at INTEGER,profile_id TEXT)")
            db.execSQL("CREATE TABLE ${table}_score ($id TEXT,category_id TEXT,score REAL,applicable INTEGER)")
        }
    }
}
