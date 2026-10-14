package org.rankoffline.app

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AnimeDatabase::class.java
    )

    @Test
    fun migration10To11PreservesAnimeData() {
        val name = "media-migration-preserves-anime"
        deleteDatabase(name)
        helper.createDatabase(name, 10).use { db ->
            db.execSQL(
                "INSERT INTO anime(id,title,mal_id,anilist_id,popularity,popularity_rank," +
                    "poster_url,remote_cover_url,local_cover_path,cover_resolution_state,format," +
                    "season_year,media_status,source,episodes,country_of_origin,average_score,start_date) " +
                    "VALUES('mal:10','Preserved Anime',10,20,99,1,'poster','remote','/covers/a.jpg'," +
                    "'DOWNLOADED','TV',2020,'FINISHED','MANGA',12,'JP',85,'2020-01-02')"
            )
            db.execSQL("INSERT INTO anime_genre VALUES('mal:10','Drama')")
            db.execSQL("INSERT INTO anime_studio VALUES('mal:10','Studio Test')")
            db.execSQL(
                "INSERT INTO rating VALUES('mal:10',0.4,8.0,8.4,'individual note'," +
                    "'Completed',123,'narrative')"
            )
            db.execSQL("INSERT INTO rating_score VALUES('mal:10','writing',9.0,1)")
            db.execSQL("INSERT INTO anime_group VALUES('group:mal:10:SERIES','SERIES','mal:10','Preserved Group')")
            db.execSQL("INSERT INTO anime_group_member VALUES('group:mal:10:SERIES','mal:10',0)")
            db.execSQL(
                "INSERT INTO group_rating VALUES('group:mal:10:SERIES',0.2,9.0,9.2," +
                    "'group note','Completed',456,'general')"
            )
            db.execSQL("INSERT INTO group_rating_score VALUES('group:mal:10:SERIES','writing',9.5,1)")
            db.execSQL("INSERT INTO catalog_metadata VALUES('catalog_version','v10')")
        }

        helper.runMigrationsAndValidate(name, 11, true, AnimeDatabase.MIGRATION_10_11).use { db ->
            db.query(
                "SELECT title,local_cover_path,cover_resolution_state,format,season_year,start_date " +
                    "FROM anime WHERE id='mal:10'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Preserved Anime", cursor.getString(0))
                assertEquals("/covers/a.jpg", cursor.getString(1))
                assertEquals("DOWNLOADED", cursor.getString(2))
                assertEquals("TV", cursor.getString(3))
                assertEquals(2020, cursor.getInt(4))
                assertEquals("2020-01-02", cursor.getString(5))
            }
            db.query(
                "SELECT additive_score,quality_score,final_score,notes,status,updated_at,profile_id " +
                    "FROM rating WHERE anime_id='mal:10'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0.4, cursor.getDouble(0), 0.0)
                assertEquals(8.0, cursor.getDouble(1), 0.0)
                assertEquals(8.4, cursor.getDouble(2), 0.0)
                assertEquals("individual note", cursor.getString(3))
                assertEquals("Completed", cursor.getString(4))
                assertEquals(123L, cursor.getLong(5))
                assertEquals("narrative", cursor.getString(6))
            }
            assertRow(db, "SELECT score FROM rating_score WHERE anime_id='mal:10'", 9.0)
            assertRow(db, "SELECT display_title FROM anime_group WHERE id='group:mal:10:SERIES'", "Preserved Group")
            assertRow(db, "SELECT position FROM anime_group_member WHERE group_id='group:mal:10:SERIES'", 0L)
            assertRow(db, "SELECT final_score FROM group_rating WHERE group_id='group:mal:10:SERIES'", 9.2)
            assertRow(db, "SELECT score FROM group_rating_score WHERE group_id='group:mal:10:SERIES'", 9.5)
            assertRow(db, "SELECT metadata_value FROM catalog_metadata WHERE metadata_key='catalog_version'", "v10")
            NEW_MEDIA_TABLES.forEach { table -> assertEquals("$table must start empty", 0L, count(db, table)) }
        }
    }

    @Test
    fun genericSchemaSupportsRepresentativeMediaAndProviderIdentity() {
        val name = "media-schema-identities"
        val db = migratedDatabase(name)
        db.use {
            listOf(
                Triple("manga:mangadex:uuid", "manga", "mangadex"),
                Triple("movie:tmdb:550", "movie", "tmdb"),
                Triple("series:tmdb:1396:season:1", "series", "tmdb"),
                Triple("book:openlibrary:OL45804W", "book", "openlibrary")
            ).forEachIndexed { index, (id, type, provider) ->
                val providerId = when (type) {
                    "manga" -> "uuid"
                    "movie" -> "550"
                    "series" -> "1396:season:1"
                    else -> "OL45804W"
                }
                db.execSQL(
                    "INSERT INTO media_item(id,media_type,provider,provider_id,title,popularity_rank) " +
                        "VALUES(?,?,?,?,?,1)",
                    arrayOf(id, type, provider, providerId, "Title $index")
                )
            }
            assertEquals(4L, count(db, "media_item"))
            assertEquals(4L, scalarLong(db, "SELECT COUNT(*) FROM media_item WHERE popularity_rank=1"))
            assertRow(
                db,
                "SELECT cover_resolution_state FROM media_item WHERE id='movie:tmdb:550'",
                "UNKNOWN"
            )

            try {
                db.execSQL(
                    "INSERT INTO media_item(id,media_type,provider,provider_id,title) " +
                        "VALUES('movie:duplicate','movie','tmdb','550','Duplicate')"
                )
                fail("Duplicate provider identity must be rejected")
            } catch (_: SQLiteConstraintException) {
                // Expected: identity is unique within a media type/provider pair.
            }
        }
    }

    @Test
    fun genericForeignKeysCascadeAndRepresentativeUsesSetNull() {
        val name = "media-schema-foreign-keys"
        val db = migratedDatabase(name)
        db.use {
            // MigrationTestHelper returns a raw connection; Room normally enables this on open.
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL(
                "INSERT INTO media_item(id,media_type,provider,provider_id,title) " +
                    "VALUES('movie:tmdb:1','movie','tmdb','1','Movie One')"
            )
            db.execSQL(
                "INSERT INTO media_item(id,media_type,provider,provider_id,title) " +
                    "VALUES('movie:tmdb:2','movie','tmdb','2','Movie Two')"
            )
            db.execSQL("INSERT INTO media_genre VALUES('movie:tmdb:1','Drama')")
            db.execSQL("INSERT INTO media_credit VALUES('movie:tmdb:1','PRODUCTION_COMPANY','Studio')")
            db.execSQL("INSERT INTO media_alias VALUES('movie:tmdb:1','Movie Alias','en','ALTERNATIVE')")
            db.execSQL(
                "INSERT INTO media_rating VALUES('movie:tmdb:1',0.0,0.0,0.0,'zero is valid'," +
                    "'Completed',100,'general')"
            )
            db.execSQL("INSERT INTO media_rating_score VALUES('movie:tmdb:1','writing',0.0,1)")
            db.execSQL(
                "INSERT INTO media_group(id,media_type,group_type,provider,provider_group_id," +
                    "display_title,representative_media_id) VALUES('movie:tmdb:collection:7','movie'," +
                    "'COLLECTION','tmdb','7','Collection','movie:tmdb:1')"
            )
            db.execSQL("INSERT INTO media_group_member VALUES('movie:tmdb:collection:7','movie:tmdb:1',0)")
            db.execSQL("INSERT INTO media_group_member VALUES('movie:tmdb:collection:7','movie:tmdb:2',1)")
            db.execSQL(
                "INSERT INTO media_group_rating VALUES('movie:tmdb:collection:7',0.3,8.0,8.3," +
                    "'independent group','Completed',200,'general')"
            )
            db.execSQL("INSERT INTO media_group_rating_score VALUES('movie:tmdb:collection:7','writing',8.0,1)")

            db.execSQL("DELETE FROM media_item WHERE id='movie:tmdb:1'")
            assertEquals(0L, count(db, "media_genre"))
            assertEquals(0L, count(db, "media_credit"))
            assertEquals(0L, count(db, "media_alias"))
            assertEquals(0L, count(db, "media_rating"))
            assertEquals(0L, count(db, "media_rating_score"))
            assertEquals(1L, count(db, "media_group"))
            db.query("SELECT representative_media_id FROM media_group WHERE id='movie:tmdb:collection:7'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertNull(cursor.getString(0))
            }
            assertEquals(1L, count(db, "media_group_member"))
            assertEquals(1L, count(db, "media_group_rating"))
            assertEquals(1L, count(db, "media_group_rating_score"))

            db.execSQL("DELETE FROM media_group WHERE id='movie:tmdb:collection:7'")
            assertEquals(0L, count(db, "media_group_member"))
            assertEquals(0L, count(db, "media_group_rating"))
            assertEquals(0L, count(db, "media_group_rating_score"))
            assertEquals(1L, count(db, "media_item"))
        }
    }

    private fun migratedDatabase(name: String): SupportSQLiteDatabase {
        deleteDatabase(name)
        helper.createDatabase(name, 10).close()
        return helper.runMigrationsAndValidate(name, 11, true, AnimeDatabase.MIGRATION_10_11)
    }

    private fun deleteDatabase(name: String) {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Long =
        scalarLong(db, "SELECT COUNT(*) FROM `$table`")

    private fun scalarLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun assertRow(db: SupportSQLiteDatabase, sql: String, expected: String) {
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getString(0))
        }
    }

    private fun assertRow(db: SupportSQLiteDatabase, sql: String, expected: Double) {
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getDouble(0), 0.0)
        }
    }

    private fun assertRow(db: SupportSQLiteDatabase, sql: String, expected: Long) {
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getLong(0))
        }
    }

    companion object {
        private val NEW_MEDIA_TABLES = listOf(
            "media_item",
            "media_genre",
            "media_credit",
            "media_alias",
            "media_group",
            "media_group_member",
            "media_rating",
            "media_rating_score",
            "media_group_rating",
            "media_group_rating_score",
            "media_catalog_metadata"
        )
    }
}
