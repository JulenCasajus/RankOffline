package org.rankoffline.app

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
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
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
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
        assertTrue(file.canonicalPath.startsWith(File(context.filesDir, "covers/movie").canonicalPath))
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
        val directory = File(context.filesDir, "covers/movie")
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "part" })
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

    private fun movie(providerId: String) = MediaItemEntity(
        id = "movie:tmdb:$providerId",
        mediaType = MediaType.MOVIE.persistedValue,
        provider = DefaultMediaCoverProvider.TMDB_PROVIDER,
        providerId = providerId,
        title = "Movie",
        remoteCoverRef = "/$providerId.jpg"
    )

    private class FakeCoverDownloader(
        private val result: MediaCoverTransferResult = MediaCoverTransferResult.DOWNLOADED
    ) : MediaCoverDownloader {
        var invocations: Int = 0
            private set

        override suspend fun download(remoteUri: URI, destination: File): MediaCoverTransferResult {
            invocations++
            if (result == MediaCoverTransferResult.DOWNLOADED) {
                val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                destination.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
                bitmap.recycle()
            } else {
                destination.writeText("partial response")
            }
            return result
        }
    }
}
