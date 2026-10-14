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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenericMediaGroupCatalogImporterTest {
    private lateinit var database: AnimeDatabase
    private lateinit var importer: GenericMediaGroupCatalogImporter
    private lateinit var mediaRepository: GenericMediaRepository
    private lateinit var groupRepository: GenericMediaGroupRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AnimeDatabase::class.java
        ).build()
        importer = GenericMediaGroupCatalogImporter(database, batchSize = 2)
        mediaRepository = GenericMediaRepository(database) { 10L }
        groupRepository = GenericMediaGroupRepository(database) { 20L }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun validImportWritesOrderedMembersAndMetadata() = runBlocking {
        insertMovies("1", "2", "3")
        val record = collection("10", listOf("2" to 2, "1" to 1, "3" to 3))
        val catalog = catalog(listOf(record))
        val manifest = manifest("v1", catalog, 1)

        assertEquals(
            MediaGroupCatalogImportResult.Imported(1),
            importer.importIfNeeded(manifest, ByteArrayInputStream(catalog))
        )
        val detail = groupRepository.groupDetail(record.id)!!
        assertEquals(listOf("1", "2", "3"), detail.members.map { it.providerId })
        assertEquals(
            "v1",
            database.mediaCatalogDao().metadata("movie", GenericMediaGroupCatalogImporter.METADATA_VERSION)
        )
        assertEquals(
            "1",
            database.mediaCatalogDao().metadata("movie", GenericMediaGroupCatalogImporter.METADATA_COUNT)
        )
    }

    @Test
    fun reimportPreservesExplicitRatingAndLocalCover() = runBlocking {
        insertMovies("1", "2", "3")
        val original = collection("10", listOf("1" to 0, "2" to 1), title = "Old")
        val firstCatalog = catalog(listOf(original))
        importer.importIfNeeded(manifest("v1", firstCatalog, 1), ByteArrayInputStream(firstCatalog))

        val groupDao = database.mediaGroupDao()
        groupDao.upsertGroup(
            groupDao.group(original.id)!!.copy(
                localCoverPath = "/private/collection.jpg",
                coverResolutionState = "AVAILABLE"
            )
        )
        groupRepository.saveSimpleGroupRating(original.id, RatingDraft(notes = "kept"), 9.0, 9.0)

        val refreshed = collection(
            "10",
            listOf("2" to 0, "3" to 1),
            title = "New",
            remoteCoverRef = "/new.jpg"
        )
        val secondCatalog = catalog(listOf(refreshed))
        importer.importIfNeeded(manifest("v2", secondCatalog, 1), ByteArrayInputStream(secondCatalog))

        val entity = groupDao.group(original.id)!!
        val detail = groupRepository.groupDetail(original.id)!!
        assertEquals("New", entity.displayTitle)
        assertEquals("/new.jpg", entity.remoteCoverRef)
        assertEquals("/private/collection.jpg", entity.localCoverPath)
        assertEquals("AVAILABLE", entity.coverResolutionState)
        assertEquals(9.0, detail.explicitRating!!.finalScore!!, 0.0)
        assertEquals(listOf("2", "3"), detail.members.map { it.providerId })
    }

    @Test
    fun missingMemberImportRollsBackCatalogAndMetadata() = runBlocking {
        insertMovies("1", "2")
        val stable = collection("10", listOf("1" to 0, "2" to 1), title = "Stable")
        val firstCatalog = catalog(listOf(stable))
        importer.importIfNeeded(manifest("v1", firstCatalog, 1), ByteArrayInputStream(firstCatalog))

        val invalid = listOf(
            stable.copy(displayTitle = "Must roll back"),
            collection("11", listOf("1" to 0, "404" to 1), title = "Invalid")
        )
        val secondCatalog = catalog(invalid)
        try {
            importer.importIfNeeded(manifest("v2", secondCatalog, 2), ByteArrayInputStream(secondCatalog))
            fail("A missing group member must fail the import")
        } catch (expected: MediaCatalogImportException) {
            assertTrue(expected.message.orEmpty().contains("Missing or wrong-media"))
        }

        assertEquals("Stable", database.mediaGroupDao().group(stable.id)!!.displayTitle)
        assertNull(database.mediaGroupDao().group("movie:tmdb:collection:11"))
        assertEquals(
            "v1",
            database.mediaCatalogDao().metadata("movie", GenericMediaGroupCatalogImporter.METADATA_VERSION)
        )
        assertNotNull(mediaRepository.item("movie:tmdb:1"))
    }

    private suspend fun insertMovies(vararg ids: String) {
        mediaRepository.upsertItems(ids.map { id ->
            MediaItemEntity(
                id = "movie:tmdb:$id",
                mediaType = "movie",
                provider = "tmdb",
                providerId = id,
                title = "Movie $id"
            )
        })
    }

    private fun collection(
        id: String,
        members: List<Pair<String, Int>>,
        title: String = "Collection $id",
        remoteCoverRef: String? = "/collection.jpg"
    ) = MediaGroupCatalogRecord(
        id = "movie:tmdb:collection:$id",
        mediaType = "movie",
        groupType = "COLLECTION",
        provider = "tmdb",
        providerGroupId = id,
        displayTitle = title,
        remoteCoverRef = remoteCoverRef,
        members = members.map { (mediaId, position) ->
            MediaGroupCatalogMember("movie:tmdb:$mediaId", position)
        }
    )

    private fun catalog(records: List<MediaGroupCatalogRecord>): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { writer ->
            records.forEach { writer.appendLine(encodeMediaGroupCatalogRecord(it)) }
        }
        return output.toByteArray()
    }

    private fun manifest(version: String, catalog: ByteArray, groupCount: Long) =
        MediaGroupCatalogManifest(
            formatVersion = MEDIA_GROUP_CATALOG_FORMAT_VERSION,
            mediaType = "movie",
            provider = "tmdb",
            catalogVersion = version,
            sha256 = MessageDigest.getInstance("SHA-256").digest(catalog)
                .joinToString("") { "%02x".format(it) },
            groupCount = groupCount
        )
}
