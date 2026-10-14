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
    fun migrate9To10PreservesIndividualRatingsAndAddsIndependentGroups() {
        val name = "group-migration-test"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        helper.createDatabase(name, 9).use { old ->
            old.execSQL("INSERT INTO anime(id,title,local_cover_path,cover_resolution_state) VALUES('mal:10','Season 1','/private/cover.jpg','AVAILABLE')")
            old.execSQL("INSERT INTO anime(id,title,cover_resolution_state) VALUES('mal:11','Season 2','UNKNOWN')")
            old.execSQL("INSERT INTO rating(anime_id,additive_score,quality_score,final_score,notes,status,updated_at,profile_id) VALUES('mal:10',0.4,8.0,8.4,'sacred note','Completed',123,'narrative')")
            old.execSQL("INSERT INTO rating_score(anime_id,category_id,score,applicable) VALUES('mal:10','writing',9.0,1)")
        }
        helper.runMigrationsAndValidate(name, 10, true, AnimeDatabase.MIGRATION_9_10).use { db ->
            db.query("SELECT additive_score,quality_score,final_score,notes,status,updated_at,profile_id FROM rating WHERE anime_id='mal:10'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0.4, c.getDouble(0), 0.0)
                assertEquals(8.0, c.getDouble(1), 0.0)
                assertEquals(8.4, c.getDouble(2), 0.0)
                assertEquals("sacred note", c.getString(3))
                assertEquals("Completed", c.getString(4))
                assertEquals(123L, c.getLong(5))
                assertEquals("narrative", c.getString(6))
            }
            db.query("SELECT score FROM rating_score WHERE anime_id='mal:10' AND category_id='writing'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(9.0, c.getDouble(0), 0.0)
            }
            db.query("SELECT local_cover_path FROM anime WHERE id='mal:10'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("/private/cover.jpg", c.getString(0))
            }
            db.execSQL("INSERT INTO anime_group VALUES('group:mal:10:SERIES','SERIES','mal:10','Season 1')")
            db.execSQL("INSERT INTO anime_group_member VALUES('group:mal:10:SERIES','mal:10',0)")
            db.execSQL("INSERT INTO anime_group_member VALUES('group:mal:10:SERIES','mal:11',1)")
            db.execSQL("INSERT INTO group_rating VALUES('group:mal:10:SERIES',0.3,9.0,9.3,'group note','Completed',456,'general')")
            db.execSQL("DELETE FROM rating WHERE anime_id='mal:10'")
            db.query("SELECT final_score,notes FROM group_rating WHERE group_id='group:mal:10:SERIES'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(9.3, c.getDouble(0), 0.0)
                assertEquals("group note", c.getString(1))
            }
        }
    }

    @Test
    fun groupedRankingUsesDerivedUntilExplicitAndNeverRewritesChildren() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java).build()
        try {
            val db = database.openHelper.writableDatabase
            listOf("a", "b", "c", "d").forEach { id ->
                db.execSQL("INSERT INTO anime(id,title,cover_resolution_state) VALUES(?,?,'UNKNOWN')", arrayOf(id, id))
            }
            db.execSQL("INSERT INTO anime_group VALUES('group:a:SERIES','SERIES','a','a')")
            db.execSQL("INSERT INTO anime_group_member VALUES('group:a:SERIES','a',0)")
            db.execSQL("INSERT INTO anime_group_member VALUES('group:a:SERIES','b',1)")
            database.animeDao().upsertRating(RatingEntity("a", 0.0, 8.6, 8.6, "a note", "Completed", 1))
            database.animeDao().upsertRating(RatingEntity("b", 0.0, 5.7, 5.7, "b note", "Completed", 1))
            database.animeDao().upsertRating(RatingEntity("c", 0.0, 0.0, 0.0, "zero valid", "Completed", 1))
            fun ranking() = database.animeDao().observeGroupedRanking(
                GroupedQueries.ranking(RankingSortOrder.FINAL_SCORE, null, AnimeFilters(), 20)
            )
            val derived = ranking().first()
            assertEquals(listOf("group:a:SERIES", "c"), derived.map { it.id })
            assertEquals(7.15, derived[0].finalScore!!, 0.000001)
            assertTrue(derived[0].derived)
            val ascending = database.animeDao().observeGroupedRanking(
                GroupedQueries.ranking(
                    RankingSortOrder.FINAL_SCORE,
                    null,
                    AnimeFilters(),
                    20,
                    descending = false
                )
            ).first()
            assertEquals(listOf("c", "group:a:SERIES"), ascending.map { it.id })
            database.animeDao().deleteRating("b")
            val oneRatedMember = ranking().first().first { it.id == "group:a:SERIES" }
            assertEquals(8.6, oneRatedMember.finalScore!!, 0.0)
            assertEquals(1, oneRatedMember.ratedCount)
            database.animeDao().upsertRating(RatingEntity("b", 0.0, 5.7, 5.7, "b note", "Completed", 1))
            database.animeDao().upsertGroupRating(
                GroupRatingEntity("group:a:SERIES", 0.0, null, null, "", "Completed", 1)
            )
            val nullExplicitFallsBack = ranking().first().first { it.id == "group:a:SERIES" }
            assertEquals(7.15, nullExplicitFallsBack.finalScore!!, 0.000001)
            assertTrue(nullExplicitFallsBack.derived)
            database.animeDao().upsertGroupRating(GroupRatingEntity("group:a:SERIES", 0.0, 9.0, 9.0, "global", "Completed", 2))
            database.animeDao().upsertRating(RatingEntity("b", 0.0, 7.0, 7.0, "changed", "Completed", 3))
            assertEquals(9.0, ranking().first()[0].finalScore!!, 0.0)
            assertEquals("changed", database.animeDao().rating("b")?.notes)
            database.animeDao().deleteGroupRating("group:a:SERIES")
            assertEquals(7.8, ranking().first()[0].finalScore!!, 0.000001)
            assertEquals("a note", database.animeDao().rating("a")?.notes)
            val catalog = database.animeDao().groupedSearch(
                GroupedQueries.catalog("", CatalogSortOrder.TITLE_ASC, AnimeFilters(), 20)
            )
            assertEquals(setOf("group:a:SERIES", "c", "d"), catalog.map { it.id }.toSet())
        } finally {
            database.close()
        }
    }

    @Test
    fun individualRankingShowsExplicitGroupOnlyWhenNoneOfItsMembersAreRated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java).build()
        try {
            val db = database.openHelper.writableDatabase
            db.execSQL("INSERT INTO anime(id,title,format,cover_resolution_state) VALUES('a','Season 1','TV','UNKNOWN')")
            db.execSQL("INSERT INTO anime(id,title,format,cover_resolution_state) VALUES('b','Season 2','TV','UNKNOWN')")
            db.execSQL("INSERT INTO anime_group VALUES('group:a:SERIES','SERIES','a','Series')")
            db.execSQL("INSERT INTO anime_group_member VALUES('group:a:SERIES','a',0)")
            db.execSQL("INSERT INTO anime_group_member VALUES('group:a:SERIES','b',1)")
            database.animeDao().upsertGroupRating(
                GroupRatingEntity("group:a:SERIES", 0.0, 8.5, 8.5, "group only", "Completed", 1)
            )
            val repository = AnimeRepository(context, database)

            val groupOnly = repository.observeRanking(mergeSeasonsEnabled = false).first()
            assertEquals(listOf("group:a:SERIES"), groupOnly.map { it.id })
            assertEquals(2, groupOnly.single().memberCount)

            database.animeDao().upsertRating(
                RatingEntity("a", 0.0, 7.0, 7.0, "individual", "Completed", 2)
            )
            val memberInstead = repository.observeRanking(mergeSeasonsEnabled = false).first()
            assertEquals(listOf("a"), memberInstead.map { it.id })

            database.animeDao().deleteRating("a")
            assertEquals(
                listOf("group:a:SERIES"),
                repository.observeRanking(mergeSeasonsEnabled = false).first().map { it.id }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun groupedFiltersUseIndependentAnyMemberConditionsAndNormalizedFormat() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java).build()
        try {
            val db = database.openHelper.writableDatabase
            db.execSQL("INSERT INTO anime(id,title,format,season_year,cover_resolution_state) VALUES('s1','Titan', 'TV',2013,'UNKNOWN')")
            db.execSQL("INSERT INTO anime(id,title,format,season_year,cover_resolution_state) VALUES('s2','Titan Final Season','ONA',2020,'UNKNOWN')")
            db.execSQL("INSERT INTO anime(id,title,format,season_year,cover_resolution_state) VALUES('m1','Titan Movie 1','MOVIE',2014,'UNKNOWN')")
            db.execSQL("INSERT INTO anime(id,title,format,season_year,cover_resolution_state) VALUES('m2','Titan Movie 2','MOVIE',2015,'UNKNOWN')")
            db.execSQL("INSERT INTO anime(id,title,format,season_year,cover_resolution_state) VALUES('o1','Titan OVA','OVA',2016,'UNKNOWN')")
            db.execSQL("INSERT INTO anime(id,title,format,season_year,cover_resolution_state) VALUES('sp1','Titan Special','SPECIAL',2017,'UNKNOWN')")
            db.execSQL("INSERT INTO anime_genre VALUES('s1','Action'),('m1','Action')")
            db.execSQL("INSERT INTO anime_studio VALUES('s2','MAPPA'),('m2','MAPPA')")
            db.execSQL("INSERT INTO anime_group VALUES('group:s1:SERIES','SERIES','s1','Titan')")
            db.execSQL("INSERT INTO anime_group VALUES('group:s1:MOVIES','MOVIES','m1','Titan Movie 1')")
            db.execSQL("INSERT INTO anime_group VALUES('group:s1:SPECIAL','SPECIAL','o1','Titan OVA')")
            db.execSQL("INSERT INTO anime_group_member VALUES('group:s1:SERIES','s1',0),('group:s1:SERIES','s2',1)")
            db.execSQL("INSERT INTO anime_group_member VALUES('group:s1:MOVIES','m1',0),('group:s1:MOVIES','m2',1)")
            db.execSQL("INSERT INTO anime_group_member VALUES('group:s1:SPECIAL','o1',0),('group:s1:SPECIAL','sp1',1)")

            suspend fun search(text: String = "", filters: AnimeFilters = AnimeFilters()) = database.animeDao().groupedSearch(
                GroupedQueries.catalog(text, CatalogSortOrder.TITLE_ASC, filters, 20)
            ).map { it.id }

            assertEquals(listOf("group:s1:SERIES"), search(filters = AnimeFilters(genre = "Action", year = 2013, studio = "MAPPA", format = "SERIES")))
            assertEquals(listOf("group:s1:MOVIES"), search(filters = AnimeFilters(format = "MOVIES")))
            assertEquals(listOf("group:s1:SERIES"), search("Final Season"))
            assertEquals(listOf("group:s1:SPECIAL"), search(filters = AnimeFilters(format = "SPECIAL")))
        } finally {
            database.close()
        }
    }

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
                RatingDraft(categoryScores = mapOf("only" to 8.5), notes = "A"),
                config
            )
            assertEquals(8.5, database.animeDao().rating("test:score")!!.finalScore!!, 0.0)
            assertEquals("A", repository.loadRating("test:score").notes)

            val simpleDraft = repository.loadRating("test:score").copy(notes = "B")
            repository.saveSimpleRating("test:score", simpleDraft, qualityScore = 9.0, finalScore = 9.24)
            assertEquals(9.0, database.animeDao().rating("test:score")!!.qualityScore!!, 0.0)
            assertEquals(9.24, database.animeDao().rating("test:score")!!.finalScore!!, 0.0)
            assertEquals(9.24, repository.observeRanking(10).first().single().finalScore!!, 0.0)
            assertEquals(8.5, repository.loadRating("test:score").categoryScores.getValue("only")!!, 0.0)
            assertEquals("B", repository.loadRating("test:score").notes)

            repository.saveElaborateRating(
                "test:score",
                repository.loadRating("test:score").copy(
                    categoryScores = mapOf("only" to 7.0),
                    notes = "C"
                ),
                config
            )
            assertEquals(7.0, database.animeDao().rating("test:score")!!.finalScore!!, 0.0)
            assertEquals("C", repository.loadRating("test:score").notes)
        } finally {
            database.close()
        }
    }

    @Test
    fun elaborateSaveUsesAndRemembersSelectedProfileWhileSimpleSavePreservesIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO anime(id,title,cover_resolution_state) VALUES('test:profile','Profile Test','UNKNOWN')"
            )
            val repository = AnimeRepository(context, database)
            val profile = DefaultSettings.defaultProfiles().resolveProfile("narrative")
            val config = DefaultSettings.defaultRatingConfig.withProfile(profile)
            repository.saveElaborateRating(
                animeId = "test:profile",
                draft = RatingDraft(
                    categoryScores = mapOf(
                        "writing" to 10.0,
                        "characters" to 0.0,
                        "visuals" to 0.0,
                        "worldbuilding" to 0.0,
                        "audio" to 0.0
                    ),
                    notes = "profile note"
                ),
                config = config,
                profileId = profile.id
            )

            val elaborate = repository.loadRating("test:profile")
            assertEquals(4.5, elaborate.finalScore!!, 0.0)
            assertEquals("narrative", elaborate.profileId)
            repository.saveSimpleRating("test:profile", elaborate, qualityScore = 9.0, finalScore = 9.0)
            assertEquals("narrative", repository.loadRating("test:profile").profileId)
            assertEquals("profile note", repository.loadRating("test:profile").notes)
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
                    if (index == 0) category.copy(weight = 95.0, color = "#A1B2C3") else category
                }
            )
        )
        settings.updateElaborateRatingEnabled(true)
        val colorReload = SettingsRepository(context, iconController, localeController)
        colorReload.loadInitialState()
        assertEquals("#A1B2C3", colorReload.state.value.ratingConfig.categories.first().color)
        assertTrue(colorReload.state.value.elaborateRatingEnabled)
        val persistedProfile = RatingProfile(
            id = "persisted-profile",
            name = "Persisted",
            weights = colorReload.state.value.ratingConfig.categories.associate { it.id to 5.0 }
        )
        settings.updateRatingProfiles(RatingProfilesConfig(listOf(persistedProfile)))
        val profileReload = SettingsRepository(context, iconController, localeController)
        profileReload.loadInitialState()
        assertEquals("persisted-profile", profileReload.state.value.ratingProfiles.profiles.single().id)
        settings.resetRatingCategories()
        assertEquals(DefaultSettings.defaultRatingConfig, settings.state.value.ratingConfig)
        assertTrue(settings.state.value.elaborateRatingEnabled)
        assertTrue(settings.state.value.showImages)
        assertEquals(SupportedLanguage.SPANISH, settings.state.value.language)
        assertTrue(activations.isEmpty())
        settings.resetRatingProfiles()

        val database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO anime(id,title,cover_resolution_state) VALUES('test:toggle','Toggle Test','UNKNOWN')"
            )
            database.animeDao().upsertRating(
                RatingEntity("test:toggle", 0.0, 8.5, 8.5, "unchanged", "Completed", 1L)
            )
            database.animeDao().upsertRatingScores(
                listOf(RatingScoreEntity("test:toggle", "writing", 7.5, true))
            )
            settings.updateElaborateRatingEnabled(false)
            assertEquals(8.5, database.animeDao().rating("test:toggle")!!.finalScore!!, 0.0)
            settings.updateElaborateRatingEnabled(true)
            assertEquals(8.5, database.animeDao().rating("test:toggle")!!.finalScore!!, 0.0)
            assertEquals("unchanged", database.animeDao().rating("test:toggle")!!.notes)
            assertEquals(7.5, database.animeDao().ratingScores("test:toggle").single().score!!, 0.0)
            assertTrue(activations.isEmpty())
            settings.updateShowImages(false)
            settings.updateLanguage(SupportedLanguage.ENGLISH)
        } finally {
            database.close()
        }
        Unit
    }

    @Test
    fun deletingCategoryRemovesItsDetailedScoresButPreservesRatingAndNotes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO anime(id,title,cover_resolution_state) VALUES('test:category','Category Test','UNKNOWN')"
            )
            database.animeDao().upsertRating(
                RatingEntity("test:category", 0.4, 8.0, 8.4, "keep notes", "Completed", 1L)
            )
            database.animeDao().upsertRatingScores(
                listOf(
                    RatingScoreEntity("test:category", "writing", 8.0, true),
                    RatingScoreEntity("test:category", "custom", 7.5, true)
                )
            )
            val repository = AnimeRepository(context, database)

            repository.deleteRatingCategory("custom")

            assertEquals(listOf("writing"), database.animeDao().ratingScores("test:category").map { it.categoryId })
            val rating = database.animeDao().rating("test:category")!!
            assertEquals(8.4, rating.finalScore!!, 0.0)
            assertEquals("keep notes", rating.notes)

            repository.deleteRatingScoresOutsideCategories(DefaultSettings.defaultRatingCategories.map { it.id })
            assertEquals(listOf("writing"), database.animeDao().ratingScores("test:category").map { it.categoryId })
            assertEquals(8.4, database.animeDao().rating("test:category")!!.finalScore!!, 0.0)
            assertEquals("keep notes", database.animeDao().rating("test:category")!!.notes)
        } finally {
            database.close()
        }
    }

    @Test
    fun rankingIncludesZeroScoreButExcludesUnratedAnime() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val db = database.openHelper.writableDatabase
            listOf("a", "b", "c", "d").forEach { id ->
                db.execSQL(
                    "INSERT INTO anime(id,title,cover_resolution_state) VALUES(?,?, 'UNKNOWN')",
                    arrayOf(id, "Anime ${id.uppercase()}")
                )
            }
            database.animeDao().upsertRating(RatingEntity("a", 0.0, 10.0, 10.0, "", "Completed", 1L))
            database.animeDao().upsertRating(RatingEntity("b", 0.0, 5.0, 5.0, "", "Completed", 2L))
            database.animeDao().upsertRating(RatingEntity("c", 0.0, 0.0, 0.0, "", "Completed", 3L))

            val ranking = database.animeDao().observeRanking(10).first()

            assertEquals(listOf("a", "b", "c"), ranking.map { it.id })
            assertEquals(0.0, ranking.last().finalScore!!, 0.0)
            assertFalse(ranking.any { it.id == "d" })
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
            assertEquals(
                listOf("delta", "beta", "Gamma", "Alpha"),
                database.animeDao().search("", 10, CatalogSortOrder.POPULARITY_REVERSE.name).map { it.title }
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
                "VALUES('mal:55',0.5,8.0,8.5,'keep v5 to v8','Completed',55)"
        )
        database.execSQL(
            "INSERT INTO rating_score(anime_id,category_id,score,applicable) " +
                "VALUES('mal:55','writing',8.0,1)"
        )
        database.close()

        helper.runMigrationsAndValidate(
            databaseName,
            8,
            true,
            AnimeDatabase.MIGRATION_5_6,
            AnimeDatabase.MIGRATION_6_7,
            AnimeDatabase.MIGRATION_7_8
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
                assertEquals("keep v5 to v8", cursor.getString(1))
            }
            migrated.query("SELECT score FROM rating_score WHERE anime_id='mal:55'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(8.0, cursor.getDouble(0), 0.0)
            }
        }
    }

    @Test
    fun migrate7To8AddsNullableProfileIdWithoutLosingRatingData() {
        val databaseName = "profile-migration-test"
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
        val database = helper.createDatabase(databaseName, 7)
        database.execSQL("INSERT INTO anime(id,title,cover_resolution_state) VALUES('mal:8','Profile Test','UNKNOWN')")
        database.execSQL(
            "INSERT INTO rating(anime_id,additive_score,quality_score,final_score,notes,status,updated_at) " +
                "VALUES('mal:8',0.5,8.0,8.5,'keep profile migration','Completed',88)"
        )
        database.execSQL(
            "INSERT INTO rating_score(anime_id,category_id,score,applicable) VALUES('mal:8','writing',8.0,1)"
        )
        database.close()

        helper.runMigrationsAndValidate(databaseName, 8, true, AnimeDatabase.MIGRATION_7_8).use { migrated ->
            migrated.query("SELECT final_score,notes,profile_id FROM rating WHERE anime_id='mal:8'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(8.5, cursor.getDouble(0), 0.0)
                assertEquals("keep profile migration", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
            migrated.query("SELECT score FROM rating_score WHERE anime_id='mal:8'").use { cursor ->
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
    fun migrate8To9AddsMetadataWithoutLosingUserData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(METADATA_MIGRATION_DB)
        val database = helper.createDatabase(METADATA_MIGRATION_DB, 8)
        database.execSQL(
            "INSERT INTO anime(id,title,local_cover_path,cover_resolution_state) " +
                "VALUES('mal:9','Migration Anime','/private/cover.jpg','AVAILABLE')"
        )
        database.execSQL(
            "INSERT INTO rating(anime_id,additive_score,quality_score,final_score,notes,status,updated_at,profile_id) " +
                "VALUES('mal:9',0.5,8.0,8.5,'keep metadata migration','Completed',99,'narrative')"
        )
        database.execSQL(
            "INSERT INTO rating_score(anime_id,category_id,score,applicable) " +
                "VALUES('mal:9','writing',9.0,1)"
        )
        database.close()

        helper.runMigrationsAndValidate(
            METADATA_MIGRATION_DB,
            9,
            true,
            AnimeDatabase.MIGRATION_8_9
        ).use { migrated ->
            migrated.query(
                "SELECT format,season_year,media_status,source,episodes,country_of_origin," +
                    "average_score,local_cover_path FROM anime WHERE id='mal:9'"
            ).use { cursor ->
                cursor.moveToFirst()
                repeat(7) { column -> assertTrue(cursor.isNull(column)) }
                assertEquals("/private/cover.jpg", cursor.getString(7))
            }
            migrated.query(
                "SELECT final_score,notes,profile_id FROM rating WHERE anime_id='mal:9'"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(8.5, cursor.getDouble(0), 0.0)
                assertEquals("keep metadata migration", cursor.getString(1))
                assertEquals("narrative", cursor.getString(2))
            }
            migrated.query(
                "SELECT score,applicable FROM rating_score WHERE anime_id='mal:9' AND category_id='writing'"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(9.0, cursor.getDouble(0), 0.0)
                assertEquals(1, cursor.getInt(1))
            }
            migrated.query("SELECT COUNT(*) FROM anime_genre").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM anime_studio").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun roomFiltersAndRankingSortsUseMetadataAndPutMissingValuesLast() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val db = database.openHelper.writableDatabase
            db.execSQL("INSERT INTO anime(id,title,format,season_year,media_status,source,episodes,cover_resolution_state) VALUES('a','Alpha Hero','TV',2024,'FINISHED','MANGA',12,'UNKNOWN')")
            db.execSQL("INSERT INTO anime(id,title,format,season_year,media_status,source,episodes,cover_resolution_state) VALUES('b','Beta Hero','TV',2024,'RELEASING','ORIGINAL',24,'UNKNOWN')")
            db.execSQL("INSERT INTO anime(id,title,format,season_year,media_status,source,episodes,cover_resolution_state) VALUES('c','Cinema','MOVIE',2020,'FINISHED','ORIGINAL',1,'UNKNOWN')")
            db.execSQL("INSERT INTO anime_genre(anime_id,genre) VALUES('a','Action'),('b','Action'),('c','Drama')")
            db.execSQL("INSERT INTO anime_studio(anime_id,studio) VALUES('a','Bones'),('b','Trigger'),('c','Bones')")
            database.animeDao().upsertRating(RatingEntity("a", 0.2, 7.0, 8.0, "", "Completed", 1))
            database.animeDao().upsertRating(RatingEntity("b", 0.8, 9.0, 7.0, "", "Completed", 2))
            database.animeDao().upsertRating(RatingEntity("c", 0.5, null, 6.0, "", "Completed", 3))
            database.animeDao().upsertRatingScores(listOf(
                RatingScoreEntity("a", "writing", 5.0, true),
                RatingScoreEntity("b", "writing", 9.0, true),
                RatingScoreEntity("c", "writing", null, false)
            ))
            val repository = AnimeRepository(context, database)

            assertEquals(
                listOf("Alpha Hero"),
                repository.search(
                    "Hero",
                    CatalogSortOrder.TITLE_ASC,
                    AnimeFilters(genre = "Action", format = "SERIES", studio = "Bones")
                ).map { it.title }
            )
            assertEquals(
                listOf("b", "a", "c"),
                repository.observeRanking(sortOrder = RankingSortOrder.QUALITY_SCORE).first().map { it.id }
            )
            assertEquals(
                listOf("a", "b", "c"),
                repository.observeRanking(
                    sortOrder = RankingSortOrder.QUALITY_SCORE,
                    descending = false
                ).first().map { it.id }
            )
            assertEquals(
                listOf("b", "c", "a"),
                repository.observeRanking(sortOrder = RankingSortOrder.PERSONAL_TASTE).first().map { it.id }
            )
            assertEquals(
                listOf("a", "c", "b"),
                repository.observeRanking(
                    sortOrder = RankingSortOrder.PERSONAL_TASTE,
                    descending = false
                ).first().map { it.id }
            )
            assertEquals(
                listOf("b", "a", "c"),
                repository.observeRanking(
                    sortOrder = RankingSortOrder.CATEGORY_SCORE,
                    categoryId = "writing"
                ).first().map { it.id }
            )
            val categoryRanking = repository.observeRanking(
                sortOrder = RankingSortOrder.CATEGORY_SCORE,
                categoryId = "writing"
            ).first()
            assertEquals(9.0, categoryRanking[0].rankingDisplayScore(RankingSortOrder.CATEGORY_SCORE)!!, 0.0)
            assertEquals(5.0, categoryRanking[1].rankingDisplayScore(RankingSortOrder.CATEGORY_SCORE)!!, 0.0)
            assertNull(categoryRanking[2].rankingDisplayScore(RankingSortOrder.CATEGORY_SCORE))
            assertEquals(
                listOf("a", "b", "c"),
                repository.observeRanking(
                    sortOrder = RankingSortOrder.CATEGORY_SCORE,
                    categoryId = "writing",
                    descending = false
                ).first().map { it.id }
            )
            assertEquals(
                listOf("a", "c"),
                repository.observeRanking(filters = AnimeFilters(studio = "Bones")).first().map { it.id }
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
        const val METADATA_MIGRATION_DB = "metadata-migration-test"
    }
}
