package org.rankoffline.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenericMediaCatalogImporterTest {
    private lateinit var database: AnimeDatabase
    private lateinit var importer: GenericMediaCatalogImporter

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AnimeDatabase::class.java
        ).build()
        importer = GenericMediaCatalogImporter(database, batchSize = 2)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun streamImportWritesCatalogRelationsAndMetadata() = runBlocking {
        val records = listOf(
            movie("1", "First", genres = listOf("Drama", "Action")),
            movie(
                "2",
                "Second",
                credits = listOf(MediaCatalogCredit("PRODUCTION_COMPANY", "Studio"))
            ),
            movie("3", "Third", aliases = listOf(MediaCatalogAlias("Tercera", "es")))
        )
        val catalog = catalog(records)
        val manifest = manifest("v1", catalog, records.size.toLong())

        assertEquals(
            MediaCatalogImportResult.Imported(3),
            importer.importIfNeeded(manifest, ByteArrayInputStream(catalog))
        )
        records.forEach { assertNotNull(database.mediaDao().item(it.id)) }
        assertEquals(2, count("SELECT COUNT(*) FROM media_genre"))
        assertEquals(1, count("SELECT COUNT(*) FROM media_credit"))
        assertEquals(1, count("SELECT COUNT(*) FROM media_alias"))
        assertEquals(
            "3",
            database.mediaCatalogDao().metadata("movie", GenericMediaCatalogImporter.METADATA_ITEM_COUNT)
        )
        assertEquals(
            manifest.sha256,
            database.mediaCatalogDao().metadata("movie", GenericMediaCatalogImporter.METADATA_SHA256)
        )

        val unreadable = object : InputStream() {
            override fun read(): Int = error("A current catalog must not be parsed")
        }
        assertEquals(MediaCatalogImportResult.NoChange, importer.importIfNeeded(manifest, unreadable))
    }

    @Test
    fun normalizedAnimeUsesTheSameCatalogEngineAndKeepsLegacyCanonicalId() = runBlocking {
        val record = MediaCatalogRecord(
            id = "mal:16498",
            mediaType = "anime",
            provider = "anime_offline_database",
            providerId = "mal:16498",
            title = "Attack on Titan",
            year = 2013,
            format = "SERIES",
            genres = listOf("Action"),
            credits = listOf(MediaCatalogCredit("STUDIO", "Wit Studio")),
            aliases = listOf(MediaCatalogAlias("Shingeki no Kyojin", "ja"))
        )
        val bytes = catalog(listOf(record))
        val manifest = manifestFor("anime", "anime_offline_database", "v1", bytes, 1)

        assertEquals(
            MediaCatalogImportResult.Imported(1),
            importer.importIfNeeded(manifest, ByteArrayInputStream(bytes))
        )
        val imported = database.mediaDao().item(record.id)
        assertNotNull(imported)
        assertEquals("anime", imported?.mediaType)
        assertEquals("SERIES", imported?.format)
        assertEquals(listOf("Action"), strings("SELECT genre FROM media_genre WHERE media_id = ?", record.id))
        assertEquals(listOf("Wit Studio"), strings("SELECT name FROM media_credit WHERE media_id = ?", record.id))
        assertEquals(
            listOf("Shingeki no Kyojin"),
            strings("SELECT title FROM media_alias WHERE media_id = ?", record.id)
        )
    }

    @Test
    fun reimportPreservesUserDataAndRefreshesCatalogRelations() = runBlocking {
        val original = movie(
            "10",
            "Old title",
            remoteCoverRef = "/old.jpg",
            genres = listOf("Drama", "Action")
        )
        val firstCatalog = catalog(listOf(original))
        importer.importIfNeeded(manifest("v1", firstCatalog, 1), ByteArrayInputStream(firstCatalog))

        val mediaDao = database.mediaDao()
        mediaDao.upsertItem(
            mediaDao.item(original.id)!!.copy(
                localCoverPath = "/private/movie-10.jpg",
                coverResolutionState = "AVAILABLE"
            )
        )
        mediaDao.upsertRating(
            MediaRatingEntity(
                original.id, 0.5, 8.0, 8.5, "note", "Rated", 7L, "general", "movies"
            )
        )
        mediaDao.upsertRatingScores(
            listOf(
                MediaRatingScoreEntity(
                    original.id,
                    "writing",
                    8.0,
                    applicable = true,
                    packId = "movies"
                )
            )
        )

        val refreshed = original.copy(
            title = "New title",
            remoteCoverRef = "/new.jpg",
            genres = listOf("Drama")
        )
        val secondCatalog = catalog(listOf(refreshed))
        importer.importIfNeeded(manifest("v2", secondCatalog, 1), ByteArrayInputStream(secondCatalog))

        val item = mediaDao.item(original.id)!!
        assertEquals("New title", item.title)
        assertEquals("/new.jpg", item.remoteCoverRef)
        assertEquals("/private/movie-10.jpg", item.localCoverPath)
        assertEquals("AVAILABLE", item.coverResolutionState)
        assertEquals(listOf("Drama"), strings("SELECT genre FROM media_genre WHERE media_id = ?", original.id))
        assertEquals(8.5, mediaDao.rating("movies", original.id)!!.finalScore!!, 0.0)
        assertEquals(8.0, mediaDao.ratingScores("movies", original.id).single().score!!, 0.0)
        assertEquals(
            "v2",
            database.mediaCatalogDao().metadata("movie", GenericMediaCatalogImporter.METADATA_VERSION)
        )
    }

    @Test
    fun invalidCatalogRollsBackItemsRelationsAndMetadata() = runBlocking {
        val original = movie("20", "Stable", genres = listOf("Drama"))
        val firstCatalog = catalog(listOf(original))
        importer.importIfNeeded(manifest("v1", firstCatalog, 1), ByteArrayInputStream(firstCatalog))

        val partialNewCatalog = catalog(
            listOf(
                original.copy(title = "Must roll back", genres = listOf("Action")),
                movie("21", "Must not remain")
            )
        )
        val invalidManifest = manifest("v2", partialNewCatalog, itemCount = 3)
        try {
            importer.importIfNeeded(invalidManifest, ByteArrayInputStream(partialNewCatalog))
            fail("Item-count mismatch should fail")
        } catch (expected: MediaCatalogImportException) {
            assertTrue(expected.message.orEmpty().contains("item count mismatch"))
        }

        assertEquals("Stable", database.mediaDao().item(original.id)!!.title)
        assertNull(database.mediaDao().item("movie:tmdb:21"))
        assertEquals(listOf("Drama"), strings("SELECT genre FROM media_genre WHERE media_id = ?", original.id))
        assertEquals(
            "v1",
            database.mediaCatalogDao().metadata("movie", GenericMediaCatalogImporter.METADATA_VERSION)
        )
    }

    @Test
    fun replacementRemovesStaleReferenceItemButKeepsCanonicalUserRating() = runBlocking {
        val removed = movie("30", "Removed")
        val retained = movie("31", "Retained")
        val first = catalog(listOf(removed, retained))
        importer.importIfNeeded(manifest("v1", first, 2), ByteArrayInputStream(first))
        database.mediaDao().upsertRating(
            MediaRatingEntity(
                removed.id, 0.0, 8.0, 8.0, "personal", "Completed", 1L,
                packId = "movies"
            )
        )

        val second = catalog(listOf(retained.copy(title = "Updated")))
        importer.importIfNeeded(manifest("v2", second, 1), ByteArrayInputStream(second))

        assertNull(database.mediaDao().item(removed.id))
        assertEquals("personal", database.mediaDao().rating("movies", removed.id)?.notes)
        assertEquals("Updated", database.mediaDao().item(retained.id)?.title)
        assertEquals(1L, database.mediaCatalogDao().catalogItemCount("movie"))
    }

    private fun movie(
        providerId: String,
        title: String,
        remoteCoverRef: String? = null,
        genres: List<String> = emptyList(),
        credits: List<MediaCatalogCredit> = emptyList(),
        aliases: List<MediaCatalogAlias> = emptyList()
    ) = MediaCatalogRecord(
        id = "movie:tmdb:$providerId",
        mediaType = "movie",
        provider = "tmdb",
        providerId = providerId,
        title = title,
        remoteCoverRef = remoteCoverRef,
        genres = genres,
        credits = credits,
        aliases = aliases
    )

    private fun catalog(records: List<MediaCatalogRecord>): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { writer ->
            records.forEach { record ->
                writer.appendLine(encodeMediaCatalogRecord(record))
            }
        }
        return output.toByteArray()
    }

    private fun manifest(version: String, catalog: ByteArray, itemCount: Long) = MediaCatalogManifest(
        formatVersion = MEDIA_CATALOG_FORMAT_VERSION,
        mediaType = "movie",
        provider = "tmdb",
        catalogVersion = version,
        sha256 = MessageDigest.getInstance("SHA-256").digest(catalog)
            .joinToString("") { "%02x".format(it) },
        itemCount = itemCount
    )

    private fun manifestFor(
        mediaType: String,
        provider: String,
        version: String,
        catalog: ByteArray,
        itemCount: Long
    ) = MediaCatalogManifest(
        formatVersion = MEDIA_CATALOG_FORMAT_VERSION,
        mediaType = mediaType,
        provider = provider,
        catalogVersion = version,
        sha256 = MessageDigest.getInstance("SHA-256").digest(catalog)
            .joinToString("") { "%02x".format(it) },
        itemCount = itemCount
    )

    private fun count(sql: String): Int {
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private fun strings(sql: String, argument: String): List<String> {
        database.openHelper.writableDatabase.query(sql, arrayOf(argument)).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }
}
