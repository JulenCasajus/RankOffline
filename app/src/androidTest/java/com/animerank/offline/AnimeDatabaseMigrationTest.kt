package com.animerank.offline

import androidx.room.testing.MigrationTestHelper
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

@RunWith(AndroidJUnit4::class)
class AnimeDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AnimeDatabase::class.java
    )

    @Test
    fun migrate4To5PreservesLegacyRatingAndCategoryIds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE anime(id TEXT PRIMARY KEY,title TEXT NOT NULL,mal_id INTEGER," +
                            "anilist_id INTEGER,poster_url TEXT,remote_cover_url TEXT,local_cover_path TEXT)"
                    )
                    db.execSQL("CREATE INDEX idx_anime_title ON anime(title COLLATE NOCASE)")
                    db.execSQL(
                        "CREATE TABLE rating(anime_id TEXT PRIMARY KEY,writing REAL,characters REAL," +
                            "engagement REAL,visuals REAL,worldbuilding REAL,personal_taste REAL NOT NULL DEFAULT 0," +
                            "base_score REAL,final_score REAL,notes TEXT NOT NULL DEFAULT ''," +
                            "status TEXT NOT NULL DEFAULT 'Completed',updated_at INTEGER NOT NULL," +
                            "FOREIGN KEY(anime_id) REFERENCES anime(id) ON DELETE CASCADE)"
                    )
                    db.execSQL("INSERT INTO anime(id,title) VALUES('mal:1','Cowboy Bebop')")
                    db.execSQL(
                        "INSERT INTO rating VALUES('mal:1',8.0,7.0,NULL,9.0,6.0,0.5,7.7,8.2," +
                            "'preserve me','Completed',1234)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).also { helper ->
            helper.writableDatabase
            helper.close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 5, true, AnimeDatabase.MIGRATION_4_5).use { db ->
            db.query("SELECT additive_score,notes,status,updated_at FROM rating WHERE anime_id='mal:1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0.5, cursor.getDouble(0), 0.0)
                assertEquals("preserve me", cursor.getString(1))
                assertEquals("Completed", cursor.getString(2))
                assertEquals(1234L, cursor.getLong(3))
            }
            db.query("SELECT category_id,score,applicable FROM rating_score WHERE anime_id='mal:1' ORDER BY category_id").use { cursor ->
                val scores = mutableMapOf<String, Pair<Double?, Int>>()
                while (cursor.moveToNext()) {
                    scores[cursor.getString(0)] =
                        (if (cursor.isNull(1)) null else cursor.getDouble(1)) to cursor.getInt(2)
                }
                assertEquals(setOf("writing", "characters", "engagement", "visuals", "worldbuilding"), scores.keys)
                assertEquals(8.0, scores.getValue("writing").first!!, 0.0)
                assertEquals(0, scores.getValue("engagement").second)
            }
        }
    }

    @Test
    fun clearingCoverPathsDoesNotDeleteRatings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO anime(id,title,local_cover_path,cover_resolution_state) " +
                    "VALUES('mal:1','Cowboy Bebop','/private/cover.jpg','AVAILABLE')"
            )
            database.animeDao().upsertRating(
                RatingEntity("mal:1", 0.5, 8.0, 8.5, "keep", "Completed", 1234)
            )

            database.animeDao().clearAllLocalCoverPaths()

            assertEquals("keep", database.animeDao().rating("mal:1")?.notes)
            assertNull(database.animeDao().allLocalCoverPaths().firstOrNull())
        } finally {
            database.close()
        }
    }

    @Test
    fun clearingSingleCoverPreservesRemoteUrlAndRating() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO anime(id,title,remote_cover_url,local_cover_path,cover_resolution_state) " +
                    "VALUES('mal:2','Trigun','https://example.com/trigun.jpg','/private/trigun.jpg','AVAILABLE')"
            )
            database.animeDao().upsertRating(
                RatingEntity("mal:2", 0.0, 9.0, 9.0, "keep this too", "Completed", 5678)
            )

            database.animeDao().clearLocalCoverPath("mal:2")

            database.openHelper.readableDatabase.query(
                "SELECT remote_cover_url,local_cover_path,cover_resolution_state FROM anime WHERE id='mal:2'"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("https://example.com/trigun.jpg", cursor.getString(0))
                assertEquals(true, cursor.isNull(1))
                assertEquals("AVAILABLE", cursor.getString(2))
            }
            assertEquals("keep this too", database.animeDao().rating("mal:2")?.notes)
        } finally {
            database.close()
        }
    }

    @Test
    fun catalogSortingAndDownloadedCoverCountUseRoom() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val db = database.openHelper.writableDatabase
            db.execSQL("INSERT INTO anime(id,title,popularity,popularity_rank,local_cover_path,cover_resolution_state) VALUES('a','Alpha',NULL,NULL,'/private/a.jpg','AVAILABLE')")
            db.execSQL("INSERT INTO anime(id,title,popularity,popularity_rank,local_cover_path,cover_resolution_state) VALUES('b','beta',100,2,'/private/b.jpg','AVAILABLE')")
            db.execSQL("INSERT INTO anime(id,title,popularity,popularity_rank,cover_resolution_state) VALUES('c','Gamma',100,1,'UNKNOWN')")
            db.execSQL("INSERT INTO anime(id,title,popularity,popularity_rank,cover_resolution_state) VALUES('d','delta',50,3,'UNKNOWN')")

            assertEquals(
                listOf("Alpha", "beta", "delta", "Gamma"),
                database.animeDao().search("", 10, CatalogSortOrder.TITLE_ASC.name).map { it.title }
            )
            assertEquals(
                listOf("Gamma", "delta", "beta", "Alpha"),
                database.animeDao().search("", 10, CatalogSortOrder.TITLE_DESC.name).map { it.title }
            )
            assertEquals(
                listOf("Gamma", "beta", "delta", "Alpha"),
                database.animeDao().search("", 10, CatalogSortOrder.POPULARITY.name).map { it.title }
            )
            assertEquals(2, database.animeDao().observeDownloadedCoverCount().first())

            database.animeDao().clearLocalCoverPath("a")

            assertEquals(1, database.animeDao().observeDownloadedCoverCount().first())
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate5To6AddsNullablePopularityWithoutLosingData() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(POPULARITY_MIGRATION_DB)
        val database = helper.createDatabase(POPULARITY_MIGRATION_DB, 5)
        database.execSQL(
            "INSERT INTO anime(id,title,cover_resolution_state) VALUES('mal:3','Monster','UNKNOWN')"
        )
        database.execSQL(
            "INSERT INTO rating(anime_id,additive_score,quality_score,final_score,notes,status,updated_at) " +
                "VALUES('mal:3',0.5,9.0,9.5,'preserve popularity migration','Completed',42)"
        )

        AnimeDatabase.MIGRATION_5_6.migrate(database)

        database.query("SELECT popularity FROM anime WHERE id='mal:3'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(true, cursor.isNull(0))
        }
        database.query("SELECT notes FROM rating WHERE anime_id='mal:3'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("preserve popularity migration", cursor.getString(0))
        }
        database.close()
    }

    @Test
    fun migrate6To7AddsUniqueNullablePopularityRankWithoutLosingData() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(RANK_MIGRATION_DB)
        val database = helper.createDatabase(RANK_MIGRATION_DB, 6)
        database.execSQL(
            "INSERT INTO anime(id,title,popularity,cover_resolution_state) " +
                "VALUES('mal:4','Pluto',1234,'UNKNOWN')"
        )
        database.execSQL(
            "INSERT INTO rating(anime_id,additive_score,quality_score,final_score,notes,status,updated_at) " +
                "VALUES('mal:4',0.0,8.0,8.0,'preserve rank migration','Completed',43)"
        )

        AnimeDatabase.MIGRATION_6_7.migrate(database)

        database.query("SELECT popularity,popularity_rank FROM anime WHERE id='mal:4'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1234, cursor.getInt(0))
            assertEquals(true, cursor.isNull(1))
        }
        database.query("SELECT notes FROM rating WHERE anime_id='mal:4'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("preserve rank migration", cursor.getString(0))
        }
        database.close()
    }

    @Test
    fun bundledCatalogImportIncludesRanksAndPreservesUserDataOnReimport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = AnimeRepository(context, database)
            assertEquals(true, repository.updateCatalogIfNeeded())
            database.openHelper.readableDatabase.query(
                "SELECT COUNT(popularity_rank),COUNT(DISTINCT popularity_rank) FROM anime"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(5_000, cursor.getInt(0))
                assertEquals(5_000, cursor.getInt(1))
            }

            val animeId = database.openHelper.readableDatabase.query(
                "SELECT id FROM anime WHERE popularity_rank=1"
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getString(0)
            }
            database.animeDao().upsertRating(
                RatingEntity(animeId, 0.5, 9.0, 9.5, "keep after catalog upsert", "Completed", 44)
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE anime SET local_cover_path='/private/keep.jpg',cover_resolution_state='AVAILABLE' " +
                    "WHERE id=?",
                arrayOf(animeId)
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE catalog_metadata SET metadata_value='force-reimport' " +
                    "WHERE metadata_key='catalog_version'"
            )

            assertEquals(true, repository.updateCatalogIfNeeded())
            assertEquals("keep after catalog upsert", database.animeDao().rating(animeId)?.notes)
            assertEquals(
                "/private/keep.jpg",
                database.openHelper.readableDatabase.query(
                    "SELECT local_cover_path FROM anime WHERE id=?",
                    arrayOf(animeId)
                ).use { cursor -> cursor.moveToFirst(); cursor.getString(0) }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun incompletePopularityResponseLeavesExistingRankUntouched() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO anime(id,title,anilist_id,popularity_rank,cover_resolution_state) " +
                    "VALUES('anilist:1','Keep me',1,1,'UNKNOWN')"
            )
            val repository = AnimeRepository(context, database)

            try {
                repository.updatePopularityRanking(
                    listOf(PopularAnimeEntry(1, 1, 999, null)),
                    limit = 100
                )
                fail("Expected incomplete response to fail validation")
            } catch (_: IllegalArgumentException) {
                database.openHelper.readableDatabase.query(
                    "SELECT popularity_rank FROM anime WHERE id='anilist:1'"
                ).use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(1, cursor.getInt(0))
                }
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
        const val POPULARITY_MIGRATION_DB = "popularity-migration-test"
        const val RANK_MIGRATION_DB = "rank-migration-test"
    }
}
