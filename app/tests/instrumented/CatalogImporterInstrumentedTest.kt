package org.rankoffline.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        ), listOf(
            """{"id":"one-two","members":[{"mediaId":"one"},{"mediaId":"two"}],"displayTitle":"One Two","groupType":"relations","representativeMediaId":"one"}"""
        ))
        CatalogImporter(database).importIfNeeded(manifest, root)
        val repository = MediaRepository(database, RatingRepository(database))

        val eitherGenre = repository.search(manifest, SearchUiState(filters = CatalogFilters(mapOf(
            "genre" to CatalogFilterValue.Choices(setOf("drama", "comedy"))
        ))), mergeEnabled = false)
        val dramaFrom2020 = repository.search(manifest, SearchUiState(filters = CatalogFilters(mapOf(
            "genre" to CatalogFilterValue.Choices(setOf("drama")),
            "year" to CatalogFilterValue.Choices(setOf("2020"))
        ))), mergeEnabled = false)
        val anyListedYear = repository.search(manifest, SearchUiState(filters = CatalogFilters(mapOf(
            "year" to CatalogFilterValue.Choices(setOf("2010", "2020"))
        ))), mergeEnabled = false)
        val everyGenre = CatalogFilters(mapOf("genre" to
            CatalogFilterValue.Choices(setOf("drama", "comedy"), matchAll = true)))
        val bothGenres = repository.search(manifest,
            SearchUiState(filters = everyGenre), mergeEnabled = false)
        val groupedBothGenres = repository.search(manifest,
            SearchUiState(filters = everyGenre), mergeEnabled = true)
        val everyYear = repository.search(manifest, SearchUiState(filters = CatalogFilters(mapOf(
            "year" to CatalogFilterValue.Choices(setOf("2010", "2020"), matchAll = true)
        ))), mergeEnabled = false)
        val years = repository.filterOptions(manifest)["year"].orEmpty()

        assertEquals(setOf("one", "two", "three"), eitherGenre.map { it.id }.toSet())
        assertEquals(listOf("one"), dramaFrom2020.map { it.id })
        assertEquals(setOf("one", "two", "three"), anyListedYear.map { it.id }.toSet())
        assertEquals(listOf("three"), bothGenres.map { it.id })
        assertEquals(setOf("one-two", "three"), groupedBothGenres.map { it.id }.toSet())
        assertTrue(everyYear.isEmpty())
        assertEquals(listOf(2010.0, 2020.0), years.map { (it as CatalogAttributeValue.Number).value })
    }

    @Test fun searchAndRankingControlsCanChangeRepeatedly() = runBlocking {
        val manifest = installCatalog(listOf(
            """{"id":"alpha","title":"Alpha","popularityRank":1,"attributes":{"genre":["drama"],"year":2020}}""",
            """{"id":"beta","title":"Beta","popularityRank":2,"attributes":{"genre":["comedy"],"year":2020}}""",
            """{"id":"gamma","title":"Gamma","popularityRank":3,"attributes":{"genre":["drama"],"year":2010}}"""
        ), listOf(
            """{"id":"pair","members":[{"mediaId":"alpha"},{"mediaId":"beta"}],"displayTitle":"Pair","groupType":"relations","representativeMediaId":"alpha"}"""
        ))
        CatalogImporter(database).importIfNeeded(manifest, root)
        val repository = MediaRepository(database, RatingRepository(database))
        listOf("alpha", "beta", "gamma").forEachIndexed { index, id ->
            database.mediaDao().upsertRating(MediaRatingEntity(11, id, 0.0,
                9.0 - index, 9.0 - index, "", "done", index.toLong()))
        }
        val pair = repository.search(manifest, SearchUiState(), mergeEnabled = true)
            .filterIsInstance<MediaListEntry.Group>().single { it.id == "pair" }
        assertEquals(2, pair.memberCount)
        assertEquals(2, pair.ratedCount)
        assertEquals(8.5, pair.finalScore!!, 0.0001)
        assertEquals(8.5, pair.qualityScore!!, 0.0001)
        assertTrue(pair.derived)
        val drama = CatalogFilters(mapOf("genre" to CatalogFilterValue.Choices(setOf("drama"))))
        val searchCases = listOf(
            Triple(false, SearchUiState(), listOf("alpha", "beta", "gamma")),
            Triple(true, SearchUiState(), listOf("pair", "gamma")),
            Triple(true, SearchUiState(filters = drama), listOf("pair", "gamma")),
            Triple(false, SearchUiState(filters = drama), listOf("alpha", "gamma")),
            Triple(false, SearchUiState(sortOrder = CatalogSortOrder.TITLE_DESC), listOf("gamma", "beta", "alpha")),
            Triple(false, SearchUiState(), listOf("alpha", "beta", "gamma"))
        )
        searchCases.forEach { (merge, state, expected) ->
            assertEquals(expected, repository.search(manifest, state, merge).map { it.id })
        }
        val rankingCases = listOf(
            Triple(false, RankingUiState(), listOf("alpha", "beta", "gamma")),
            Triple(true, RankingUiState(), listOf("pair", "gamma")),
            Triple(false, RankingUiState(filters = drama), listOf("alpha", "gamma")),
            Triple(true, RankingUiState(filters = drama), listOf("pair", "gamma")),
            Triple(false, RankingUiState(filters = CatalogFilters(mapOf(
                "genre" to CatalogFilterValue.Choices(setOf("drama")),
                "year" to CatalogFilterValue.Choices(setOf("2020"))
            ))), listOf("alpha")),
            Triple(true, RankingUiState(filters = CatalogFilters(mapOf(
                "genre" to CatalogFilterValue.Choices(setOf("drama", "comedy"), matchAll = true)
            ))), listOf("pair")),
            Triple(false, RankingUiState(descending = false), listOf("gamma", "beta", "alpha")),
            Triple(false, RankingUiState(), listOf("alpha", "beta", "gamma"))
        )
        rankingCases.forEach { (merge, state, expected) ->
            assertEquals(expected, repository.ranking(manifest, state, merge).map { it.id })
        }
    }

    @Test fun mergedGroupSubtitlesContainDistinctMemberValuesInManifestOrder() = runBlocking {
        val manifest = installCatalog(listOf(
            """{"id":"one","title":"One","attributes":{"genre":["drama","comedy"],"year":2020}}""",
            """{"id":"two","title":"Two","attributes":{"genre":["drama"],"year":2021}}""",
            """{"id":"three","title":"Three","attributes":{"genre":["comedy"],"year":2020}}"""
        ), listOf(
            """{"id":"trio","members":[{"mediaId":"one"},{"mediaId":"two"},{"mediaId":"three"}],"displayTitle":"Trio","groupType":"relations","representativeMediaId":"one"}"""
        ))
        CatalogImporter(database).importIfNeeded(manifest, root)
        val repository = MediaRepository(database, RatingRepository(database))
        val group = repository.search(manifest, SearchUiState(), mergeEnabled = true)
            .filterIsInstance<MediaListEntry.Group>().single()
        val parts = group.subtitleParts(manifest.subtitleDefinitions(), "en")

        assertEquals(3, group.memberCount)
        assertEquals(listOf("genre", "year"), parts.map { it.fieldId })
        assertEquals(listOf("drama", "comedy"), parts[0].values)
        assertEquals(listOf("2020", "2021"), parts[1].values)
        assertFalse(parts[0].compact)
        assertFalse(parts[1].compact)
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

    private fun installCatalog(lines: List<String>, groups: List<String> = emptyList()): PackManifest {
        val bytes = (lines.joinToString("\n") + "\n").toByteArray()
        val groupBytes = (groups.joinToString("\n") + "\n").toByteArray()
        val manifestText = TestFixtures.manifestJson(catalog = bytes,
            merge = groups.isNotEmpty(), groups = groupBytes)
        root.resolve("catalog").mkdirs()
        root.resolve("catalog/items.jsonl").writeBytes(bytes)
        if (groups.isNotEmpty()) {
            root.resolve("groups").mkdirs()
            root.resolve("groups/groups.jsonl").writeBytes(groupBytes)
        }
        root.resolve(PackInstaller.PACK_MANIFEST).writeText(manifestText)
        root.resolve(PackInstaller.COMPLETE_MARKER).writeText("test")
        return PackManifestParser.parse(manifestText)
    }
}
