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
        mediaRepository = GenericMediaRepository(database, now = { 10L })
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
    fun normalizedAnimeRelationsUseTheSamePrecomputedGroupImporter() = runBlocking {
        val items = listOf("mal:1", "mal:2").mapIndexed { index, id ->
            MediaItemEntity(
                id = id,
                mediaType = "anime",
                provider = "anime_offline_database",
                providerId = id,
                title = "Season ${index + 1}"
            )
        }
        mediaRepository.upsertItems(items)
        val record = MediaGroupCatalogRecord(
            id = "group:mal:1:SERIES",
            mediaType = "anime",
            groupType = "RELATIONS",
            provider = "anime_offline_database",
            providerGroupId = "group:mal:1:SERIES",
            displayTitle = "Example series",
            representativeMediaId = "mal:1",
            memberCount = 2,
            popularity = 100.0,
            popularityRank = 1,
            members = listOf(
                MediaGroupCatalogMember("mal:1", 0),
                MediaGroupCatalogMember("mal:2", 1)
            )
        )
        val bytes = catalog(listOf(record))
        val manifest = MediaGroupCatalogManifest(
            formatVersion = MEDIA_GROUP_CATALOG_FORMAT_VERSION,
            mediaType = "anime",
            provider = "anime_offline_database",
            catalogVersion = "v1",
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) },
            groupCount = 1
        )

        assertEquals(
            MediaGroupCatalogImportResult.Imported(1),
            importer.importIfNeeded(manifest, ByteArrayInputStream(bytes), "RELATIONS")
        )
        val detail = groupRepository.groupDetail(record.id)
        assertNotNull(detail)
        assertEquals(listOf("mal:1", "mal:2"), detail?.members?.map(MediaItem::id))
        assertEquals(2, database.mediaGroupDao().membershipCount("anime", "RELATIONS"))
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

    @Test
    fun replacementDropsStaleRelationshipRowsButPreservesUserOwnedGroupRating() = runBlocking {
        insertMovies("1", "2", "3")
        val removed = collection("10", listOf("1" to 0, "2" to 1), title = "Removed")
        val retained = collection("11", listOf("2" to 0, "3" to 1), title = "Retained")
        val first = catalog(listOf(removed, retained))
        importer.importIfNeeded(manifest("v1", first, 2), ByteArrayInputStream(first), "COLLECTION")
        groupRepository.saveSimpleGroupRating(removed.id, RatingDraft(notes = "personal"), 7.0, 7.0)

        val second = catalog(listOf(retained.copy(displayTitle = "Updated")))
        importer.importIfNeeded(manifest("v2", second, 1), ByteArrayInputStream(second), "COLLECTION")

        assertNull(database.mediaGroupDao().group(removed.id))
        assertEquals("personal", database.mediaGroupDao().groupRating("movies", removed.id)?.notes)
        assertEquals("Updated", database.mediaGroupDao().group(retained.id)?.displayTitle)
    }

    @Test
    fun manifestDeclaredGroupTypeIsEnforcedBeforeReady() = runBlocking {
        insertMovies("1", "2")
        val bytes = catalog(listOf(collection("10", listOf("1" to 0, "2" to 1))))

        assertTrue(runCatching {
            importer.importIfNeeded(manifest("v1", bytes, 1), ByteArrayInputStream(bytes), "SEASONS")
        }.isFailure)
        assertEquals(0, database.mediaGroupDao().groupCount("movie", "COLLECTION"))
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
        representativeMediaId = "movie:tmdb:${members.minBy { it.second }.first}",
        memberCount = members.size,
        popularity = 100.0,
        popularityRank = 1,
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
