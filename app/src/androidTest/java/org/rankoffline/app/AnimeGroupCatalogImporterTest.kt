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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnimeGroupCatalogImporterTest {
    private lateinit var database: AnimeDatabase
    private lateinit var importer: AnimeGroupCatalogImporter

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AnimeDatabase::class.java
        ).build()
        importer = AnimeGroupCatalogImporter(database)
        listOf("anime:1", "anime:2", "anime:3").forEachIndexed { index, id ->
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO anime(id,title,popularity_rank,cover_resolution_state) VALUES(?,?,?,'UNKNOWN')",
                arrayOf(id, "Season ${index + 1}", index + 1)
            )
        }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun importsExplicitPrecomputedGraphAndReusesDurableMetadata() = runBlocking {
        val compressed = catalog(
            """{"id":"anime:group:show-1","type":"TV","rootAnimeId":"anime:1","displayTitle":"Show","members":[{"animeId":"anime:1","position":0},{"animeId":"anime:2","position":1},{"animeId":"anime:3","position":2}]}"""
        )
        val manifest = manifest("v1", compressed, groups = 1, members = 3)

        assertTrue(importer.importIfNeeded(manifest, ByteArrayInputStream(compressed)))
        assertFalse(importer.importIfNeeded(manifest, ByteArrayInputStream(compressed)))
        assertEquals(1, database.animeDao().catalogGroupCount())
        assertEquals(3, database.animeDao().catalogGroupMembershipCount())
        assertEquals(
            listOf("anime:1", "anime:2", "anime:3"),
            database.animeDao().groupMembers("anime:group:show-1").map { it.id }
        )
    }

    @Test
    fun missingCatalogMemberRollsBackWithoutReplacingExistingGraph() = runBlocking {
        val stable = catalog(
            """{"id":"anime:group:show-1","type":"TV","rootAnimeId":"anime:1","displayTitle":"Stable","members":[{"animeId":"anime:1","position":0},{"animeId":"anime:2","position":1}]}"""
        )
        importer.importIfNeeded(manifest("v1", stable, 1, 2), ByteArrayInputStream(stable))
        val invalid = catalog(
            """{"id":"anime:group:show-2","type":"TV","rootAnimeId":"anime:1","displayTitle":"Invalid","members":[{"animeId":"anime:1","position":0},{"animeId":"anime:404","position":1}]}"""
        )

        assertTrue(runCatching {
            importer.importIfNeeded(manifest("v2", invalid, 1, 2), ByteArrayInputStream(invalid))
        }.isFailure)
        assertEquals("Stable", database.animeDao().group("anime:group:show-1")?.displayTitle)
        assertEquals(2, database.animeDao().catalogGroupMembershipCount())
    }

    @Test
    fun changedSnapshotReconcilesOnlyGraphDataAndPreservesGroupRating() = runBlocking {
        val original = catalog(
            """{"id":"anime:group:show-1","type":"TV","rootAnimeId":"anime:1","displayTitle":"Old title","members":[{"animeId":"anime:1","position":0},{"animeId":"anime:2","position":1}]}""",
            """{"id":"anime:group:stale","type":"TV","rootAnimeId":"anime:2","displayTitle":"Stale","members":[{"animeId":"anime:2","position":0},{"animeId":"anime:3","position":1}]}"""
        )
        importer.importIfNeeded(manifest("v1", original, 2, 4), ByteArrayInputStream(original))
        database.animeDao().upsertGroupRating(
            GroupRatingEntity("anime:group:show-1", 0.5, 8.0, 8.5, "mine", "Rated", 10L)
        )

        val changed = catalog(
            """{"id":"anime:group:show-1","type":"TV","rootAnimeId":"anime:1","displayTitle":"New title","members":[{"animeId":"anime:3","position":0},{"animeId":"anime:1","position":1}]}"""
        )
        assertTrue(importer.importIfNeeded(manifest("v2", changed, 1, 2), ByteArrayInputStream(changed)))

        assertEquals("New title", database.animeDao().group("anime:group:show-1")?.displayTitle)
        assertEquals(
            listOf("anime:3", "anime:1"),
            database.animeDao().groupMembers("anime:group:show-1").map { it.id }
        )
        assertNull(database.animeDao().group("anime:group:stale"))
        assertEquals("mine", database.animeDao().groupRating("anime:group:show-1")?.notes)
        assertEquals(1, database.animeDao().catalogGroupCount())
        assertEquals(2, database.animeDao().catalogGroupMembershipCount())
    }

    @Test
    fun normalizedGroupImportMigratesLegacyRatingAndScores() = runBlocking {
        val legacyId = "anime:group:film:MOVIE"
        val normalizedId = "anime:group:film:MOVIES"
        database.animeDao().upsertGroupRating(
            GroupRatingEntity(legacyId, 0.0, 9.0, 9.0, "legacy", "Rated", 20L)
        )
        database.animeDao().upsertGroupRatingScores(
            listOf(GroupRatingScoreEntity(legacyId, "story", 9.0, true))
        )
        val graph = catalog(
            """{"id":"$normalizedId","type":"MOVIES","rootAnimeId":"anime:1","displayTitle":"Film","members":[{"animeId":"anime:1","position":0},{"animeId":"anime:2","position":1}]}"""
        )

        importer.importIfNeeded(manifest("v1", graph, 1, 2), ByteArrayInputStream(graph))

        assertEquals("legacy", database.animeDao().groupRating(normalizedId)?.notes)
        val migratedScore = database.animeDao().groupRatingScores(normalizedId).single()
        assertNotNull(migratedScore.score)
        assertEquals(9.0, migratedScore.score!!, 0.0)
    }

    private fun catalog(vararg lines: String): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { writer ->
            lines.forEach(writer::appendLine)
        }
        output.toByteArray()
    }

    private fun manifest(
        version: String,
        compressed: ByteArray,
        groups: Long,
        members: Long
    ) = AnimeGroupCatalogManifest(
        formatVersion = ANIME_GROUP_CATALOG_FORMAT_VERSION,
        provider = "fixture",
        catalogVersion = version,
        sha256 = MessageDigest.getInstance("SHA-256").digest(compressed)
            .joinToString("") { "%02x".format(it) },
        groupCount = groups,
        membershipCount = members
    )
}
