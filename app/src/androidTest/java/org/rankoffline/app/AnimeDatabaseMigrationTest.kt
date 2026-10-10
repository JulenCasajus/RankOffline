package org.rankoffline.app

import androidx.room.testing.MigrationTestHelper
import androidx.room.Room
import android.content.res.Configuration
import android.os.LocaleList
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AnimeDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AnimeDatabase::class.java
    )

    @Test
    fun everySupportedLocaleResolvesRepresentativeResources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SupportedLanguage.entries.forEach { language ->
            val configuration = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language.languageTag)))
            }
            val localized = context.createConfigurationContext(configuration)
            assertTrue("Missing settings title for ${language.languageTag}", localized.getString(R.string.settings_title).isNotBlank())
            assertTrue("Missing language title for ${language.languageTag}", localized.getString(R.string.language_title).isNotBlank())
        }
    }

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
    fun simpleAndElaborateSavesShareFinalScoreAndPreserveCategories() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO anime(id,title,cover_resolution_state) VALUES('test:score','Score Test','UNKNOWN')"
            )
            val repository = AnimeRepository(context, database)
            val config = RatingSystemConfig(
                categories = listOf(RatingCategory("only", "Only", "", 100.0, "#000000", 0)),
                additiveBonus = null
            )

            repository.saveElaborateRating(
                "test:score",
                RatingDraft(categoryScores = mapOf("only" to 8.43), notes = "keep notes"),
                config
            )
            assertEquals(8.43, database.animeDao().rating("test:score")!!.finalScore!!, 0.0)

            val elaborateDraft = repository.loadRating("test:score")
            repository.saveSimpleRating("test:score", elaborateDraft, 9.15)
            assertEquals(9.15, database.animeDao().rating("test:score")!!.finalScore!!, 0.0)
            assertEquals(9.15, repository.observeRanking(10).first().single().finalScore!!, 0.0)
            assertEquals(8.43, repository.loadRating("test:score").categoryScores.getValue("only")!!, 0.0)
            assertEquals("keep notes", database.animeDao().rating("test:score")!!.notes)

            repository.saveElaborateRating(
                "test:score",
                repository.loadRating("test:score").copy(categoryScores = mapOf("only" to 7.82)),
                config
            )
            assertEquals(7.82, database.animeDao().rating("test:score")!!.finalScore!!, 0.0)
        } finally {
            database.close()
        }
    }

    @Test
    fun readingSettingsAndChangingRatingModeDoNotTouchAliasesOrScores() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activations = mutableListOf<AppIcon>()
        val appliedLanguages = mutableListOf<SupportedLanguage>()
        val iconController = object : AppIconAliasController {
            override fun activate(icon: AppIcon, previousIcon: AppIcon) { activations += icon }
        }
        val localeController = AppLocaleController { appliedLanguages += it }
        val settings = SettingsRepository(context, iconController, localeController)
        settings.loadInitialState()
        assertTrue(activations.isEmpty())
        settings.updateShowImages(true)
        settings.updateElaborateRatingEnabled(false)
        settings.updateLanguage(SupportedLanguage.SPANISH)
        val reloadedSettings = SettingsRepository(context, iconController, localeController)
        reloadedSettings.loadInitialState()
        assertTrue(reloadedSettings.state.value.showImages)
        assertEquals(false, reloadedSettings.state.value.elaborateRatingEnabled)
        assertEquals(SupportedLanguage.SPANISH, reloadedSettings.state.value.language)
        assertTrue(appliedLanguages.contains(SupportedLanguage.SPANISH))
        assertTrue(activations.isEmpty())
        settings.updateRatingConfig(
            DefaultSettings.defaultRatingConfig.copy(
                categories = DefaultSettings.defaultRatingCategories.mapIndexed { index, category ->
                    if (index == 0) category.copy(weight = 99.0) else category
                }
            )
        )
        settings.resetRatingConfig()
        assertEquals(DefaultSettings.defaultRatingConfig, settings.state.value.ratingConfig)
        assertTrue(settings.state.value.showImages)
        assertEquals(false, settings.state.value.elaborateRatingEnabled)
        assertEquals(SupportedLanguage.SPANISH, settings.state.value.language)
        assertTrue(activations.isEmpty())

        val database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO anime(id,title,cover_resolution_state) VALUES('test:toggle','Toggle Test','UNKNOWN')"
            )
            database.animeDao().upsertRating(
                RatingEntity("test:toggle", 0.0, 8.37, 8.37, "", "Completed", 1L)
            )
            settings.updateElaborateRatingEnabled(false)
            assertEquals(8.37, database.animeDao().rating("test:toggle")!!.finalScore!!, 0.0)
            settings.updateElaborateRatingEnabled(true)
            assertEquals(8.37, database.animeDao().rating("test:toggle")!!.finalScore!!, 0.0)
            assertTrue(activations.isEmpty())
            settings.updateShowImages(false)
            settings.updateLanguage(SupportedLanguage.ENGLISH)
        } finally {
            database.close()
        }
        Unit
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
    fun migrate5ToCurrentPreservesRatingsScoresNotesAndCoverMetadata() {
        val databaseName = "migration-5-to-current"
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
        val database = helper.createDatabase(databaseName, 5)
        database.execSQL(
            "INSERT INTO anime(id,title,remote_cover_url,local_cover_path,cover_resolution_state) " +
                "VALUES('mal:55','Migration Five','https://example.com/55.jpg','/private/55.jpg','AVAILABLE')"
        )
        database.execSQL(
            "INSERT INTO rating(anime_id,additive_score,quality_score,final_score,notes,status,updated_at) " +
                "VALUES('mal:55',0.5,8.0,8.5,'keep v5 to v7','Completed',55)"
        )
        database.execSQL(
            "INSERT INTO rating_score(anime_id,category_id,score,applicable) " +
                "VALUES('mal:55','writing',8.0,1)"
        )
        database.close()

        helper.runMigrationsAndValidate(
            databaseName,
            7,
            true,
            AnimeDatabase.MIGRATION_5_6,
            AnimeDatabase.MIGRATION_6_7
        ).use { migrated ->
            migrated.query(
                "SELECT popularity,popularity_rank,local_cover_path FROM anime WHERE id='mal:55'"
            ).use { cursor ->
                cursor.moveToFirst()
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
                assertEquals("/private/55.jpg", cursor.getString(2))
            }
            migrated.query("SELECT final_score,notes FROM rating WHERE anime_id='mal:55'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(8.5, cursor.getDouble(0), 0.0)
                assertEquals("keep v5 to v7", cursor.getString(1))
            }
            migrated.query("SELECT score FROM rating_score WHERE anime_id='mal:55'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(8.0, cursor.getDouble(0), 0.0)
            }
        }
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
            assertFalse(repository.updateCatalogIfNeeded())
            database.openHelper.readableDatabase.query(
                "SELECT COUNT(popularity_rank),COUNT(DISTINCT popularity_rank) FROM anime"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(5_000, cursor.getInt(0))
                assertEquals(5_000, cursor.getInt(1))
            }
            assertEquals(
                (1..10).toList(),
                database.animeDao()
                    .search("", 10, CatalogSortOrder.POPULARITY.name)
                    .map { it.popularityRank }
            )

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
