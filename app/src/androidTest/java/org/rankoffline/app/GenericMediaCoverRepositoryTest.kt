package org.rankoffline.app

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import java.net.UnknownHostException
import kotlinx.coroutines.flow.first
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
class GenericMediaCoverRepositoryTest {
    private val context = IsolatedTestContext(
        InstrumentationRegistry.getInstrumentation().targetContext,
        "generic-cover-repository"
    )
    private lateinit var database: AnimeDatabase
    private lateinit var mediaRepository: GenericMediaRepository
    private val createdFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java).build()
        mediaRepository = GenericMediaRepository(database)
    }

    @After
    fun tearDown() {
        createdFiles.forEach(File::delete)
        database.close()
        context.delete()
    }

    @Test
    fun successfulDownloadStoresPrivateFileAndMarksAvailable() = runBlocking {
        val item = movie("cover-success")
        mediaRepository.upsertItem(item)
        val downloader = FakeCoverDownloader()
        val repository = GenericMediaCoverRepository(context, database, downloader = downloader)

        assertEquals(CoverDownloadResult.Downloaded, repository.downloadCover(item.id))

        val stored = database.mediaDao().item(item.id)!!
        val file = File(requireNotNull(stored.localCoverPath)).also(createdFiles::add)
        assertTrue(file.isFile)
        assertTrue(
            file.canonicalPath.startsWith(
                File(context.filesDir, "downloaded_covers/movies").canonicalPath
            )
        )
        assertEquals(CoverResolutionState.AVAILABLE.name, stored.coverResolutionState)
        assertEquals(1, downloader.invocations)
    }

    @Test
    fun existingValidCoverSkipsDownloadAndMissingFileIsRepaired() = runBlocking {
        val item = movie("cover-existing")
        mediaRepository.upsertItem(item)
        val downloader = FakeCoverDownloader()
        val repository = GenericMediaCoverRepository(context, database, downloader = downloader)

        repository.downloadCover(item.id)
        val file = File(database.mediaDao().item(item.id)!!.localCoverPath!!).also(createdFiles::add)
        assertEquals(CoverDownloadResult.AlreadyAvailable, repository.downloadCover(item.id))
        assertEquals(1, downloader.invocations)

        assertTrue(file.delete())
        assertEquals(CoverDownloadResult.Downloaded, repository.downloadCover(item.id))
        assertEquals(2, downloader.invocations)
        assertTrue(file.isFile)
    }

    @Test
    fun failedDownloadLeavesNoPartialFileOrAvailableState() = runBlocking {
        val item = movie("cover-failure")
        mediaRepository.upsertItem(item)
        val downloader = FakeCoverDownloader(MediaCoverTransferResult.TEMPORARY_ERROR)
        val repository = GenericMediaCoverRepository(context, database, downloader = downloader)

        assertEquals(CoverDownloadResult.TemporaryError, repository.downloadCover(item.id))

        val stored = database.mediaDao().item(item.id)!!
        assertNull(stored.localCoverPath)
        assertEquals(CoverResolutionState.UNKNOWN.name, stored.coverResolutionState)
        val directory = File(context.filesDir, "downloaded_covers/movies")
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "part" })
    }

    @Test
    fun invalidImageResponseIsPermanentUnavailableAndNeverPersisted() = runBlocking {
        val item = movie("invalid-image")
        mediaRepository.upsertItem(item)
        val repository = GenericMediaCoverRepository(
            context,
            database,
            downloader = MediaCoverDownloader { _, destination ->
                destination.writeText("not an image")
                MediaCoverTransferResult.DOWNLOADED
            }
        )

        assertEquals(CoverDownloadResult.NotFound, repository.downloadCover(item.id))
        val stored = database.mediaDao().item(item.id)!!
        assertNull(stored.localCoverPath)
        assertEquals(CoverResolutionState.NOT_FOUND.name, stored.coverResolutionState)
    }

    @Test
    fun normalizedAnimeWithoutDirectUrlUsesProviderResolver() = runBlocking {
        val item = MediaItemEntity(
            id = "mal:16498",
            mediaType = MediaType.ANIME.persistedValue,
            provider = "anime_offline_database",
            providerId = "mal:16498",
            title = "Anime"
        )
        mediaRepository.upsertItem(item)
        val downloader = FakeCoverDownloader()
        val repository = GenericMediaCoverRepository(
            context,
            database,
            downloader = downloader,
            animeResolver = AnimeMediaCoverResolver { listOf(URI("https://cdn.myanimelist.net/images/a.jpg")) }
        )

        assertEquals(CoverDownloadResult.Downloaded, repository.downloadCover(item.id))
        assertEquals(1, downloader.invocations)
        database.mediaDao().item(item.id)?.localCoverPath?.let(::File)?.let(createdFiles::add)
        Unit
    }

    @Test
    fun animeProviderResolutionFailureIsRecoverableAndDoesNotPersistFalseSuccess() = runBlocking {
        val item = MediaItemEntity(
            id = "mal:offline",
            mediaType = MediaType.ANIME.persistedValue,
            provider = "anime_offline_database",
            providerId = "mal:offline",
            title = "Offline anime"
        )
        mediaRepository.upsertItem(item)
        val repository = GenericMediaCoverRepository(
            context,
            database,
            downloader = FakeCoverDownloader(),
            animeResolver = AnimeMediaCoverResolver { throw UnknownHostException("offline") }
        )

        assertEquals(CoverDownloadResult.NetworkError, repository.downloadCover(item.id))
        assertNull(database.mediaDao().item(item.id)?.localCoverPath)
        assertEquals(
            CoverResolutionState.UNKNOWN.name,
            database.mediaDao().item(item.id)?.coverResolutionState
        )
    }

    @Test
    fun bulkAnimeCoverCleanupAcceptsOnlyKnownLegacyCoverDirectory() = runBlocking {
        val legacyFile = File(context.filesDir, "covers/legacy-anime-test.jpg")
        legacyFile.parentFile?.mkdirs()
        legacyFile.writeText("legacy")
        createdFiles += legacyFile
        val item = MediaItemEntity(
            id = "mal:legacy-cover",
            mediaType = MediaType.ANIME.persistedValue,
            provider = "anime_offline_database",
            providerId = "mal:legacy-cover",
            title = "Legacy cover",
            localCoverPath = legacyFile.absolutePath,
            coverResolutionState = CoverResolutionState.AVAILABLE.name
        )
        mediaRepository.upsertItem(item)
        val repository = GenericMediaCoverRepository(context, database)

        assertEquals(1, repository.clearDownloadedCovers(MediaType.ANIME))
        assertFalse(legacyFile.exists())
        assertNull(database.mediaDao().item(item.id)?.localCoverPath)
    }

    @Test
    fun openLibraryNotFoundCandidateFallsBackToNextKnownIdentifier() = runBlocking {
        val item = MediaItemEntity(
            id = "book:openlibrary:OL45804W",
            mediaType = MediaType.BOOK.persistedValue,
            provider = DefaultMediaCoverProvider.OPEN_LIBRARY_PROVIDER,
            providerId = "OL45804W",
            title = "Book",
            remoteCoverRef = encodeOpenLibraryCoverReference(
                coverId = "123",
                isbn13 = "9780140328721",
                isbn10 = null,
                editionOlid = null,
                workOlid = "OL45804W"
            )
        )
        mediaRepository.upsertItem(item)
        val downloader = FakeCoverDownloader(
            results = listOf(
                MediaCoverTransferResult.NOT_FOUND,
                MediaCoverTransferResult.DOWNLOADED
            )
        )
        val repository = GenericMediaCoverRepository(context, database, downloader = downloader)

        assertEquals(CoverDownloadResult.Downloaded, repository.downloadCover(item.id))
        assertEquals(2, downloader.invocations)
        assertEquals(
            listOf(
                "https://covers.openlibrary.org/b/id/123-L.jpg?default=false",
                "https://covers.openlibrary.org/b/isbn/9780140328721-L.jpg?default=false"
            ),
            downloader.requestedUris.map(URI::toASCIIString)
        )
        database.mediaDao().item(item.id)?.localCoverPath?.let(::File)?.let(createdFiles::add)
        Unit
    }

    @Test
    fun removeCoverPreservesMediaAndRating() = runBlocking {
        val item = movie("cover-remove")
        mediaRepository.upsertItem(item)
        val repository = GenericMediaCoverRepository(
            context,
            database,
            downloader = FakeCoverDownloader()
        )
        repository.downloadCover(item.id)
        val file = File(database.mediaDao().item(item.id)!!.localCoverPath!!).also(createdFiles::add)
        mediaRepository.saveSimpleRating(item.id, RatingDraft(notes = "keep"), 8.0, 8.0)

        assertTrue(repository.removeLocalCover(item.id))

        val stored = database.mediaDao().item(item.id)
        assertNotNull(stored)
        assertNull(stored!!.localCoverPath)
        assertEquals(CoverResolutionState.UNKNOWN.name, stored.coverResolutionState)
        assertFalse(file.exists())
        assertEquals(8.0, mediaRepository.rating(item.id)!!.finalScore!!, 0.0)
    }

    @Test
    fun individualCoverPathIsObservedFromRoomAndSurvivesRepositoryRecreation() = runBlocking {
        val item = movie("cover-observed")
        mediaRepository.upsertItem(item)
        val repository = GenericMediaCoverRepository(
            context,
            database,
            downloader = FakeCoverDownloader()
        )

        assertEquals(CoverDownloadResult.Downloaded, repository.downloadCover(item.id))
        val expected = requireNotNull(repository.observeLocalCoverPath(item.id).first())
        createdFiles += File(expected)

        val recreated = GenericMediaCoverRepository(context, database, downloader = FakeCoverDownloader())
        assertEquals(expected, recreated.observeLocalCoverPath(item.id).first())
    }

    @Test
    fun groupCoverUsesMediaGroupObservationAndSurvivesRepositoryRecreation() = runBlocking {
        val first = movie("group-member-1")
        val second = movie("group-member-2")
        mediaRepository.upsertItems(listOf(first, second))
        val groupId = "movie:tmdb:collection:cover-observed"
        GenericMediaGroupRepository(database).upsertGroup(
            MediaGroupEntity(
                id = groupId,
                mediaType = MediaType.MOVIE.persistedValue,
                groupType = "COLLECTION",
                provider = DefaultMediaCoverProvider.TMDB_PROVIDER,
                providerGroupId = "cover-observed",
                displayTitle = "Observed collection",
                representativeMediaId = first.id,
                remoteCoverRef = "/collection.jpg"
            ),
            listOf(
                MediaGroupMemberEntity(groupId, first.id, 0),
                MediaGroupMemberEntity(groupId, second.id, 1)
            )
        )
        val repository = GenericMediaGroupCoverRepository(
            context,
            database,
            downloader = FakeCoverDownloader()
        )

        assertEquals(CoverDownloadResult.Downloaded, repository.downloadCover(groupId))
        val expected = requireNotNull(repository.observeLocalCoverPath(groupId).first())
        createdFiles += File(expected)
        assertNull(database.mediaDao().item(groupId))

        val recreated = GenericMediaGroupCoverRepository(
            context,
            database,
            downloader = FakeCoverDownloader()
        )
        assertEquals(expected, recreated.observeLocalCoverPath(groupId).first())
    }

    @Test
    fun individualAndGroupCoverPathsSurviveDatabaseRecreation() = runBlocking {
        val databaseName = "cover-persistence-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        var persistedDatabase = Room.databaseBuilder(context, AnimeDatabase::class.java, databaseName).build()
        val item = movie("process-item")
        val second = movie("process-second")
        GenericMediaRepository(persistedDatabase).upsertItems(listOf(item, second))
        val groupId = "movie:tmdb:collection:process"
        GenericMediaGroupRepository(persistedDatabase).upsertGroup(
            MediaGroupEntity(
                id = groupId,
                mediaType = MediaType.MOVIE.persistedValue,
                groupType = "COLLECTION",
                provider = DefaultMediaCoverProvider.TMDB_PROVIDER,
                providerGroupId = "process",
                displayTitle = "Persistent collection",
                representativeMediaId = item.id,
                remoteCoverRef = "/process.jpg"
            ),
            listOf(
                MediaGroupMemberEntity(groupId, item.id, 0),
                MediaGroupMemberEntity(groupId, second.id, 1)
            )
        )
        val itemRepository = GenericMediaCoverRepository(
            context,
            persistedDatabase,
            downloader = FakeCoverDownloader()
        )
        val groupRepository = GenericMediaGroupCoverRepository(
            context,
            persistedDatabase,
            downloader = FakeCoverDownloader()
        )
        assertEquals(CoverDownloadResult.Downloaded, itemRepository.downloadCover(item.id))
        assertEquals(CoverDownloadResult.Downloaded, groupRepository.downloadCover(groupId))
        val itemPath = requireNotNull(itemRepository.observeLocalCoverPath(item.id).first())
        val groupPath = requireNotNull(groupRepository.observeLocalCoverPath(groupId).first())
        createdFiles += listOf(File(itemPath), File(groupPath))
        persistedDatabase.close()

        persistedDatabase = Room.databaseBuilder(context, AnimeDatabase::class.java, databaseName).build()
        try {
            assertEquals(
                itemPath,
                GenericMediaCoverRepository(context, persistedDatabase).observeLocalCoverPath(item.id).first()
            )
            assertEquals(
                groupPath,
                GenericMediaGroupCoverRepository(context, persistedDatabase)
                    .observeLocalCoverPath(groupId).first()
            )
        } finally {
            persistedDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun movie(providerId: String) = MediaItemEntity(
        id = "movie:tmdb:$providerId",
        mediaType = MediaType.MOVIE.persistedValue,
        provider = DefaultMediaCoverProvider.TMDB_PROVIDER,
        providerId = providerId,
        title = "Movie",
        remoteCoverRef = "/$providerId.jpg"
    )

    private class FakeCoverDownloader(
        private val result: MediaCoverTransferResult = MediaCoverTransferResult.DOWNLOADED,
        private val results: List<MediaCoverTransferResult> = emptyList()
    ) : MediaCoverDownloader {
        var invocations: Int = 0
            private set
        val requestedUris = mutableListOf<URI>()

        override suspend fun download(remoteUri: URI, destination: File): MediaCoverTransferResult {
            requestedUris += remoteUri
            invocations++
            val currentResult = results.getOrNull(invocations - 1) ?: result
            if (currentResult == MediaCoverTransferResult.DOWNLOADED) {
                val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                destination.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
                bitmap.recycle()
            } else {
                destination.writeText("partial response")
            }
            return currentResult
        }
    }
}
