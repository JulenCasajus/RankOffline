package org.rankoffline.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnimeAliasCatalogImporterTest {
    private lateinit var database: AnimeDatabase
    private lateinit var importer: AnimeAliasCatalogImporter

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AnimeDatabase::class.java
        ).build()
        importer = AnimeAliasCatalogImporter(database, batchSize = 1)
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO anime(id,title,local_cover_path,cover_resolution_state) " +
                "VALUES('mal:1','Canonical','/private/cover.jpg','AVAILABLE')"
        )
        db.execSQL("INSERT INTO rating VALUES('mal:1',0.5,8.0,8.5,'keep','Completed',1,'general')")
        db.execSQL("INSERT INTO anime_group VALUES('group:1','SERIES','mal:1','Group')")
        db.execSQL("INSERT INTO anime_group_member VALUES('group:1','mal:1',1)")
        db.execSQL("INSERT INTO group_rating VALUES('group:1',0.5,9.0,9.5,'keep group','Completed',2,'general')")
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun refreshReplacesCatalogAliasesAndPreservesUserData() = runBlocking {
        val first = catalog("Old Alias", release = "2026-42")
        assertEquals(
            AnimeAliasCatalogImportResult.Imported(1, 1),
            importer.importIfNeeded(first.manifest, ByteArrayInputStream(first.bytes))
        )
        assertEquals(
            AnimeAliasCatalogImportResult.NoChange,
            importer.importIfNeeded(first.manifest, ByteArrayInputStream(first.bytes))
        )

        val second = catalog("New Alias", release = "2026-43")
        importer.importIfNeeded(second.manifest, ByteArrayInputStream(second.bytes))

        assertEquals(listOf("New Alias"), aliasTitles())
        assertEquals("keep", database.animeDao().rating("mal:1")?.notes)
        assertEquals("/private/cover.jpg", scalarString("SELECT local_cover_path FROM anime WHERE id='mal:1'"))
        assertEquals("keep group", scalarString("SELECT notes FROM group_rating WHERE group_id='group:1'"))
    }

    @Test
    fun missingAnimeRollsBackAliasRefresh() = runBlocking {
        val valid = catalog("Existing Alias")
        importer.importIfNeeded(valid.manifest, ByteArrayInputStream(valid.bytes))
        val invalid = catalog("Unknown Alias", animeId = "missing")

        runCatching {
            importer.importIfNeeded(invalid.manifest, ByteArrayInputStream(invalid.bytes))
        }.onSuccess { throw AssertionError("Missing Anime ID must fail import") }

        assertEquals(listOf("Existing Alias"), aliasTitles())
        assertNotNull(database.animeDao().rating("mal:1"))
    }

    private fun catalog(
        alias: String,
        animeId: String = "mal:1",
        release: String = "2026-42"
    ): TestCatalog {
        val json =
            """{"animeId":"$animeId","aliases":[{"title":"$alias","language":null,"kind":"anime-offline-database"}]}""" +
                "\n"
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        val bytes = output.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return TestCatalog(
            AnimeAliasCatalogManifest(
                formatVersion = 1,
                source = ANIME_ALIAS_SOURCE,
                sourceOriginalProject = ANIME_ALIAS_ORIGINAL_PROJECT,
                sourceRelease = release,
                sourceAsset = "fixture.jsonl",
                sourceAssetSha256 = "a".repeat(64),
                license = ANIME_ALIAS_LICENSE,
                recordCount = 1,
                aliasCount = 1,
                sha256 = sha
            ),
            bytes
        )
    }

    private fun aliasTitles(): List<String> =
        database.openHelper.readableDatabase.query("SELECT title FROM anime_alias ORDER BY title").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun scalarString(sql: String): String =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

    private data class TestCatalog(val manifest: AnimeAliasCatalogManifest, val bytes: ByteArray)
}
