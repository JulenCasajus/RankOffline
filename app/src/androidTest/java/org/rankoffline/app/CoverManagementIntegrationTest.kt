package org.rankoffline.app

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
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
class CoverManagementIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AnimeDatabase
    private lateinit var mediaRepository: GenericMediaRepository
    private lateinit var groupRepository: GenericMediaGroupRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java).build()
        mediaRepository = GenericMediaRepository(database)
        groupRepository = GenericMediaGroupRepository(database)
    }

    @After
    fun tearDown() {
        listOf(MediaType.MANGA, MediaType.MOVIE, MediaType.SERIES, MediaType.BOOK).forEach { type ->
            File(context.filesDir, "covers/${type.persistedValue}").deleteRecursively()
        }
        database.close()
    }

    @Test
    fun downloadedCoverCountIsMediaScoped() = runBlocking {
        val downloader = FakeDownloader()
        val covers = GenericMediaCoverRepository(context, database, downloader = downloader)
        val manga = item(MediaType.MANGA, "count-manga", "mangadex", "cover.png")
        val movie = item(MediaType.MOVIE, "count-movie", "tmdb", "/cover.jpg")
        mediaRepository.upsertItems(listOf(manga, movie))

        assertEquals(CoverDownloadResult.Downloaded, covers.downloadCover(manga.id))
        assertEquals(CoverDownloadResult.Downloaded, covers.downloadCover(movie.id))

        assertEquals(1, covers.observeDownloadedCoverCount(MediaType.MANGA).first())
        assertEquals(1, covers.observeDownloadedCoverCount(MediaType.MOVIE).first())
        assertEquals(0, covers.observeDownloadedCoverCount(MediaType.BOOK).first())
    }

    @Test
    fun clearCoversAffectsOnlyActiveMedia() = runBlocking {
        val covers = GenericMediaCoverRepository(context, database, downloader = FakeDownloader())
        val manga = item(MediaType.MANGA, "clear-manga", "mangadex", "cover.png")
        val movie = item(MediaType.MOVIE, "clear-movie", "tmdb", "/cover.jpg")
        mediaRepository.upsertItems(listOf(manga, movie))
        mediaRepository.saveSimpleRating(movie.id, RatingDraft(), 8.0, 8.0)
        assertEquals(CoverDownloadResult.Downloaded, covers.downloadCover(manga.id))
        assertEquals(CoverDownloadResult.Downloaded, covers.downloadCover(movie.id))
        val moviePath = database.mediaDao().item(movie.id)!!.localCoverPath

        assertEquals(1, covers.clearDownloadedCovers(MediaType.MANGA))

        assertNull(database.mediaDao().item(manga.id)!!.localCoverPath)
        assertNotNull(database.mediaDao().item(movie.id)!!.localCoverPath)
        assertTrue(File(requireNotNull(moviePath)).isFile)
        assertEquals(8.0, mediaRepository.rating(movie.id)!!.finalScore!!, 0.0)
    }

    @Test
    fun ratedCoverPreloadSelectsOnlyRatedMissingResolvableCovers() = runBlocking {
        val downloader = FakeDownloader()
        val covers = GenericMediaCoverRepository(context, database, downloader = downloader)
        val ratedZero = item(MediaType.MOVIE, "rated-zero", "tmdb", "/zero.jpg")
        val ratedEight = item(MediaType.MOVIE, "rated-eight", "tmdb", "/eight.jpg")
        val unrated = item(MediaType.MOVIE, "unrated", "tmdb", "/unrated.jpg")
        val existing = item(MediaType.MOVIE, "existing", "tmdb", "/existing.jpg")
        val noRemote = item(MediaType.MOVIE, "no-remote", "tmdb", null)
        mediaRepository.upsertItems(listOf(ratedZero, ratedEight, unrated, existing, noRemote))
        mediaRepository.saveSimpleRating(ratedZero.id, RatingDraft(), 0.0, 0.0)
        mediaRepository.saveSimpleRating(ratedEight.id, RatingDraft(), 8.0, 8.0)
        mediaRepository.saveSimpleRating(existing.id, RatingDraft(), 7.0, 7.0)
        mediaRepository.saveSimpleRating(noRemote.id, RatingDraft(), 6.0, 6.0)
        covers.downloadCover(existing.id)
        downloader.invocations.set(0)
        val manager = CoverPreloadManager(
            AnimeRepository(context, database),
            CoverRepository(context, AnimeRepository(context, database)),
            covers
        )

        manager.preload(MediaType.MOVIE)

        assertEquals(2, downloader.invocations.get())
        assertEquals(CoverPreloadStatus.COMPLETED, manager.progress.value.status)
        assertEquals(4, manager.progress.value.target)
        assertEquals(2, manager.progress.value.downloaded)
        assertEquals(2, manager.progress.value.existing)
        assertNull(database.mediaDao().item(unrated.id)!!.localCoverPath)
        assertNotNull(database.mediaDao().item(ratedZero.id)!!.localCoverPath)
    }

    @Test
    fun groupCoverOperationsPreserveRatingAndMembers() = runBlocking {
        val first = item(MediaType.MOVIE, "group-first", "tmdb", "/first.jpg")
        val second = item(MediaType.MOVIE, "group-second", "tmdb", "/second.jpg")
        mediaRepository.upsertItems(listOf(first, second))
        val group = MediaGroupEntity(
            id = "movie:tmdb:collection:17",
            mediaType = MediaType.MOVIE.persistedValue,
            groupType = "COLLECTION",
            provider = "tmdb",
            providerGroupId = "17",
            displayTitle = "Collection",
            remoteCoverRef = "/collection.jpg"
        )
        groupRepository.upsertGroup(
            group,
            listOf(
                MediaGroupMemberEntity(group.id, first.id, 0),
                MediaGroupMemberEntity(group.id, second.id, 1)
            )
        )
        groupRepository.saveSimpleGroupRating(group.id, RatingDraft(), 9.0, 9.0)
        val covers = GenericMediaGroupCoverRepository(context, database, downloader = FakeDownloader())

        assertEquals(CoverDownloadResult.Downloaded, covers.downloadCover(group.id))
        val path = database.mediaGroupDao().group(group.id)!!.localCoverPath
        assertTrue(File(requireNotNull(path)).isFile)
        assertTrue(covers.removeLocalCover(group.id))

        assertFalse(File(path).exists())
        assertNull(database.mediaGroupDao().group(group.id)!!.localCoverPath)
        assertEquals(9.0, database.mediaGroupDao().groupRating(group.id)!!.finalScore!!, 0.0)
        assertEquals(listOf(first.id, second.id), database.mediaGroupDao().groupMembers(group.id).map { it.id })
    }

    private fun item(type: MediaType, suffix: String, provider: String, remote: String?) = MediaItemEntity(
        id = "${type.persistedValue}:$provider:$suffix",
        mediaType = type.persistedValue,
        provider = provider,
        providerId = if (type == MediaType.MANGA) TEST_MANGADEX_ID else suffix,
        title = suffix,
        remoteCoverRef = remote
    )

    private class FakeDownloader : MediaCoverDownloader {
        val invocations = AtomicInteger()

        override suspend fun download(remoteUri: URI, destination: File): MediaCoverTransferResult {
            invocations.incrementAndGet()
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            return MediaCoverTransferResult.DOWNLOADED
        }
    }

    private companion object {
        const val TEST_MANGADEX_ID = "00000000-0000-0000-0000-000000000001"
    }
}
