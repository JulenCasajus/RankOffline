package org.rankoffline.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogImporterInstrumentedTest {
    private lateinit var database: RankOfflineDatabase
    private lateinit var root: File

    @Before fun createDatabaseAndStorage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        root = File(context.cacheDir, "catalog-import-tests/pack-11")
        root.parentFile?.deleteRecursively()
    }

    @After fun closeDatabaseAndStorage() {
        database.close()
        root.parentFile?.deleteRecursively()
    }

    @Test fun realImporterPersistsItemsAliasesAttributesAndFingerprint() = runBlocking {
        val manifest = installCatalog(listOf(
            """{"id":"item:1","title":"Title","popularityRank":1,"attributes":{"genre":["drama"]},"aliases":[{"title":"Alias"}]}"""
        ))
        val importer = CatalogImporter(database, batchSize = 1)

        assertEquals(CatalogImportResult.Imported(1, 0), importer.importIfNeeded(manifest, root))
        assertEquals("Title", database.mediaDao().item(11, "item:1")?.title)
        assertEquals(listOf("Alias"), database.mediaDao().aliases(11, listOf("item:1")).map { it.title })
        assertEquals(listOf("drama"), database.mediaDao().textFilterOptions(11, "genre"))
        assertEquals(CatalogImportResult.NoChange, importer.importIfNeeded(manifest, root))
        assertTrue(importer.isCurrentFast(manifest, root))
    }

    @Test fun malformedRecordRollsBackTheWholeCatalogTransaction() = runBlocking {
        val manifest = installCatalog(listOf(
            """{"id":"valid","title":"Valid"}""",
            """{"id":"invalid","title":"Invalid","unknown":true}"""
        ))

        val error = runCatching { CatalogImporter(database, 1).importIfNeeded(manifest, root) }
            .exceptionOrNull()

        assertTrue(error is CatalogImportException)
        assertEquals(0, database.mediaCatalogDao().itemCount(11))
        assertEquals(null, database.mediaCatalogDao().metadata(11, CatalogImporter.METADATA_FINGERPRINT))
    }

    @Test fun duplicateIdentityOnFreshImportRollsBackAllRows() = runBlocking {
        val manifest = installCatalog(listOf(
            """{"id":"one","title":"One","provider":"source","providerId":"42"}""",
            """{"id":"two","title":"Two","provider":"source","providerId":"42"}"""
        ))

        val error = runCatching { CatalogImporter(database, 400).importIfNeeded(manifest, root) }
            .exceptionOrNull()

        assertTrue(error is CatalogImportException)
        assertEquals(0L, database.mediaCatalogDao().itemCount(11))
        assertEquals(null, database.mediaCatalogDao().metadata(11, CatalogImporter.METADATA_FINGERPRINT))
    }

    @Test fun duplicateItemIdOnFreshImportRollsBackAllRows() = runBlocking {
        val manifest = installCatalog(listOf(
            """{"id":"same","title":"One"}""",
            """{"id":"same","title":"Two"}"""
        ))

        val error = runCatching { CatalogImporter(database, 1).importIfNeeded(manifest, root) }
            .exceptionOrNull()

        assertTrue(error is CatalogImportException)
        assertEquals(0L, database.mediaCatalogDao().itemCount(11))
    }

    @Test fun refreshedCatalogRemovesMissingItemButKeepsItsPersonalRating() = runBlocking {
        var manifest = installCatalog(listOf(
            """{"id":"keep","title":"Keep"}""",
            """{"id":"removed","title":"Removed"}"""
        ))
        val importer = CatalogImporter(database)
        importer.importIfNeeded(manifest, root)
        val rating = MediaRatingEntity(11, "removed", 0.0, 9.0, 9.0, "personal", "done", 1)
        database.mediaDao().upsertRating(rating)

        manifest = installCatalog(listOf("""{"id":"keep","title":"Updated"}"""))
        assertEquals(CatalogImportResult.Imported(1, 0), importer.importIfNeeded(manifest, root))

        assertEquals("Updated", database.mediaDao().item(11, "keep")?.title)
        assertEquals(null, database.mediaDao().item(11, "removed"))
        assertEquals(rating, database.mediaDao().rating(11, "removed"))
    }

    @Test fun filtersUseOrWithinOneFieldAndAndBetweenDifferentFields() = runBlocking {
        val manifest = installCatalog(listOf(
            """{"id":"one","title":"One","attributes":{"genre":["drama"],"year":2020}}""",
            """{"id":"two","title":"Two","attributes":{"genre":["comedy"],"year":2020}}""",
            """{"id":"three","title":"Three","attributes":{"genre":["drama","comedy"],"year":2010}}"""
        ))
        CatalogImporter(database).importIfNeeded(manifest, root)
        val repository = MediaRepository(database, RatingRepository(database))

        val eitherGenre = repository.search(manifest, SearchUiState(filters = CatalogFilters(mapOf(
            "genre" to CatalogFilterValue.Choices(setOf("drama", "comedy"))
        ))), mergeEnabled = false)
        val dramaFrom2020 = repository.search(manifest, SearchUiState(filters = CatalogFilters(mapOf(
            "genre" to CatalogFilterValue.Choices(setOf("drama")),
            "year" to CatalogFilterValue.Range(min = 2020.0, max = null)
        ))), mergeEnabled = false)

        assertEquals(setOf("one", "two", "three"), eitherGenre.map { it.id }.toSet())
        assertEquals(listOf("one"), dramaFrom2020.map { it.id })
    }

    @Test fun uninstallAndReinstallKeepRatingsWithoutDuplicatingCatalogDetails() = runBlocking {
        val manifest = installCatalog(listOf(
            """{"id":"one","title":"One","attributes":{"genre":["drama"]},"aliases":[{"title":"Alias"}]}"""
        ))
        val importer = CatalogImporter(database)
        importer.importIfNeeded(manifest, root)
        val rating = MediaRatingEntity(11, "one", 0.5, 7.0, 7.5, "Keep", "done", 1)
        database.mediaDao().upsertRating(rating)

        database.mediaCatalogDao().deleteCatalogReferenceData(11)
        assertEquals(0L, database.mediaCatalogDao().itemCount(11))
        assertEquals(rating, database.mediaDao().rating(11, "one"))

        importer.importIfNeeded(manifest, root)
        assertEquals(1L, database.mediaCatalogDao().itemCount(11))
        assertEquals(listOf("drama"), database.mediaDao().textFilterOptions(11, "genre"))
        assertEquals(listOf("Alias"), database.mediaDao().aliases(11, listOf("one")).map { it.title })
        assertEquals(rating, database.mediaDao().rating(11, "one"))
    }

    private fun installCatalog(lines: List<String>): PackManifest {
        val bytes = (lines.joinToString("\n") + "\n").toByteArray()
        val manifestText = TestFixtures.manifestJson(catalog = bytes)
        root.resolve("catalog").mkdirs()
        root.resolve("catalog/items.jsonl").writeBytes(bytes)
        root.resolve(PackInstaller.PACK_MANIFEST).writeText(manifestText)
        root.resolve(PackInstaller.COMPLETE_MARKER).writeText("test")
        return PackManifestParser.parse(manifestText)
    }
}
