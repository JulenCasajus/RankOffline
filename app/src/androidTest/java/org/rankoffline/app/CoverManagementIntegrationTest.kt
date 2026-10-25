package org.rankoffline.app

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private val context = IsolatedTestContext(
        InstrumentationRegistry.getInstrumentation().targetContext,
        "cover-management"
    )
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
        MediaType.entries.forEach { type ->
            File(context.filesDir, "covers/${type.persistedValue}").deleteRecursively()
            File(context.filesDir, "downloaded_covers/${type.toPackId().value}").deleteRecursively()
            File(context.filesDir, "packs/${type.toPackId().value}").deleteRecursively()
        }
        database.close()
        context.delete()
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
    fun topCoverPreloadCountsExistingUnavailableAndActualDownloads() = runBlocking {
        val downloader = FakeDownloader()
        val covers = GenericMediaCoverRepository(context, database, downloader = downloader)
        val ratedZero = item(MediaType.MOVIE, "rated-zero", "tmdb", "/zero.jpg").copy(popularityRank = 1)
        val ratedEight = item(MediaType.MOVIE, "rated-eight", "tmdb", "/eight.jpg").copy(popularityRank = 2)
        val unrated = item(MediaType.MOVIE, "unrated", "tmdb", "/unrated.jpg").copy(popularityRank = 5)
        val existing = item(MediaType.MOVIE, "existing", "tmdb", "/existing.jpg").copy(popularityRank = 3)
        val noRemote = item(MediaType.MOVIE, "no-remote", "tmdb", null).copy(popularityRank = 4)
        mediaRepository.upsertItems(listOf(ratedZero, ratedEight, unrated, existing, noRemote))
        mediaRepository.saveSimpleRating(ratedZero.id, RatingDraft(), 0.0, 0.0)
        mediaRepository.saveSimpleRating(ratedEight.id, RatingDraft(), 8.0, 8.0)
        mediaRepository.saveSimpleRating(existing.id, RatingDraft(), 7.0, 7.0)
        mediaRepository.saveSimpleRating(noRemote.id, RatingDraft(), 6.0, 6.0)
        covers.downloadCover(existing.id)
        downloader.invocations.set(0)
        val manager = CoverPreloadManager(covers)

        manager.preloadTop(MediaType.MOVIE, 10, oneCoverPerGroup = false)

        assertEquals(3, downloader.invocations.get())
        assertEquals(CoverPreloadStatus.COMPLETED, manager.progress.value.status)
        assertEquals(10, manager.progress.value.target)
        assertEquals(10, manager.progress.value.processed)
        assertEquals(3, manager.progress.value.downloaded)
        assertEquals(1, manager.progress.value.existing)
        assertEquals(6, manager.progress.value.unavailable)
        assertNotNull(database.mediaDao().item(unrated.id)!!.localCoverPath)
        assertNotNull(database.mediaDao().item(ratedZero.id)!!.localCoverPath)
    }

    @Test
    fun retryableFailuresRunOneFinalPassWithoutInflatingProgress() = runBlocking {
        val succeedsOnRetry = item(MediaType.MOVIE, "retry-success", "tmdb", "/retry-success.jpg")
            .copy(popularityRank = 1)
        val permanentlyMissing = item(MediaType.MOVIE, "missing", "tmdb", "/missing.jpg")
            .copy(popularityRank = 2)
        val stillOffline = item(MediaType.MOVIE, "offline", "tmdb", "/offline.jpg")
            .copy(popularityRank = 3)
        mediaRepository.upsertItems(listOf(succeedsOnRetry, permanentlyMissing, stillOffline))
        val downloader = ScriptedDownloader(
            mapOf(
                "retry-success" to listOf(
                    MediaCoverTransferResult.TEMPORARY_ERROR,
                    MediaCoverTransferResult.DOWNLOADED
                ),
                "missing" to listOf(MediaCoverTransferResult.NOT_FOUND),
                "offline" to listOf(
                    MediaCoverTransferResult.NETWORK_ERROR,
                    MediaCoverTransferResult.NETWORK_ERROR
                )
            )
        )
        val manager = CoverPreloadManager(
            GenericMediaCoverRepository(context, database, downloader = downloader)
        )
        val snapshots = mutableListOf<CoverPreloadProgress>()

        val result = manager.preloadTop(MediaType.MOVIE, 3, false, snapshots::add)

        assertEquals(CoverPreloadStatus.COMPLETED, result.status)
        assertEquals(3, result.processed)
        assertEquals(1, result.downloaded)
        assertEquals(1, result.unavailable)
        assertEquals(1, result.errors)
        assertEquals(2, downloader.attempts("retry-success"))
        assertEquals(1, downloader.attempts("missing"))
        assertEquals(2, downloader.attempts("offline"))
        assertTrue(snapshots.any { it.status == CoverPreloadStatus.RETRYING_COVERS })
        assertTrue(snapshots.all { it.processed <= it.target })
    }

    @Test
    fun preparingProgressIsImmediateAndPackScoped() {
        val manager = CoverPreloadManager(
            GenericMediaCoverRepository(context, database, downloader = FakeDownloader())
        )

        manager.markPreparing(MediaType.SERIES, 250)

        assertEquals(
            CoverPreloadStatus.PREPARING_COVER_LIST,
            manager.progressByMedia.value.getValue(MediaType.SERIES).status
        )
        assertEquals(250, manager.progressByMedia.value.getValue(MediaType.SERIES).target)
        assertEquals(
            CoverPreloadStatus.IDLE,
            manager.progressByMedia.value.getValue(MediaType.MANGA).status
        )
    }

    @Test
    fun coverOperationsQueuePerPackAndPauseOnlyForActiveArchiveNetwork() = runBlocking {
        val firstRequestStarted = CompletableDeferred<Unit>()
        val releaseFirstRequest = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val downloader = MediaCoverDownloader { _, destination ->
            if (calls.incrementAndGet() == 1) {
                firstRequestStarted.complete(Unit)
                releaseFirstRequest.await()
            }
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            MediaCoverTransferResult.DOWNLOADED
        }
        mediaRepository.upsertItems(
            listOf(
                item(MediaType.MOVIE, "queued-movie", "tmdb", "/movie.jpg").copy(popularityRank = 1),
                item(MediaType.MANGA, "queued-manga", "mangadex", "manga.jpg").copy(popularityRank = 1)
            )
        )
        val scheduler = PackOperationScheduler()
        val manager = CoverPreloadManager(
            GenericMediaCoverRepository(context, database, downloader = downloader),
            scheduler
        )

        scheduler.archiveTransferStarted()
        val movie = launch { manager.preloadTop(MediaType.MOVIE, 1, false) }
        withTimeout(2_000) {
            manager.progressByMedia.first {
                it.getValue(MediaType.MOVIE).status == CoverPreloadStatus.DOWNLOADING_COVERS
            }
        }
        assertEquals(0, calls.get())
        scheduler.archiveTransferFinished()
        withTimeout(2_000) { firstRequestStarted.await() }

        val manga = launch { manager.preloadTop(MediaType.MANGA, 1, false) }
        withTimeout(2_000) {
            manager.progressByMedia.first {
                it.getValue(MediaType.MANGA).status == CoverPreloadStatus.WAITING_FOR_COVERS
            }
        }
        releaseFirstRequest.complete(Unit)
        withTimeout(5_000) {
            movie.join()
            manga.join()
        }

        assertEquals(CoverPreloadStatus.COMPLETED, manager.progressByMedia.value.getValue(MediaType.MOVIE).status)
        assertEquals(CoverPreloadStatus.COMPLETED, manager.progressByMedia.value.getValue(MediaType.MANGA).status)
        assertEquals(2, calls.get())
    }

    @Test
    fun animePreloadResolvesStableProviderIdWithoutDirectUrl() = runBlocking {
        val anime = MediaItemEntity(
            id = "mal:5114",
            mediaType = MediaType.ANIME.persistedValue,
            provider = "anime_offline_database",
            providerId = "mal:5114",
            title = "Anime"
        )
        mediaRepository.upsertItem(anime)
        mediaRepository.saveSimpleRating(anime.id, RatingDraft(), 9.0, 9.0)
        val downloader = FakeDownloader()
        val covers = GenericMediaCoverRepository(
            context,
            database,
            downloader = downloader,
            animeResolver = AnimeMediaCoverResolver {
                listOf(URI("https://cdn.myanimelist.net/images/preload.jpg"))
            }
        )

        CoverPreloadManager(covers).preloadTop(MediaType.ANIME, 10, oneCoverPerGroup = false)

        assertEquals(1, downloader.invocations.get())
        assertNotNull(database.mediaDao().item(anime.id)?.localCoverPath)
    }

    @Test
    fun cancellingExplicitPreloadIsTerminalAndRemovesPartialFile() = runBlocking {
        val item = item(MediaType.MOVIE, "cancel", "tmdb", "/cancel.jpg")
            .copy(popularityRank = 1)
        mediaRepository.upsertItem(item)
        val covers = GenericMediaCoverRepository(
            context,
            database,
            downloader = MediaCoverDownloader { _, destination ->
                destination.writeBytes(byteArrayOf(1, 2, 3))
                awaitCancellation()
            }
        )
        val manager = CoverPreloadManager(covers)
        val job = launch { manager.preloadTop(MediaType.MOVIE, 250, false) }
        withTimeout(2_000) {
            manager.progress.first { it.status == CoverPreloadStatus.DOWNLOADING_COVERS }
        }

        job.cancelAndJoin()

        assertEquals(CoverPreloadStatus.CANCELLED, manager.progress.value.status)
        assertNull(database.mediaDao().item(item.id)!!.localCoverPath)
        assertTrue(
            File(context.filesDir, "downloaded_covers/movies")
                .walkTopDown().none { it.isFile && it.extension == "part" }
        )
    }

    @Test
    fun oneHundredPackCoversPlusSeventyFiveDownloadsCountAndDeleteAsOneCache() = runBlocking {
        val packDirectory = File(context.filesDir, "packs/movies").apply { mkdirs() }
        val coverDirectory = File(packDirectory, "covers").apply { mkdirs() }
        val indexRows = JSONArray()
        val coverFiles = (1..100).map { index ->
            val relative = "covers/fixture-$index.png"
            val file = File(packDirectory, relative)
            FakeDownloader().download(URI("https://image.tmdb.org/fixture-$index.png"), file)
            indexRows.put(JSONObject().put("canonicalId", "movie:tmdb:fixture-$index").put("path", relative))
            PackManifestFile(relative, file.length(), "0".repeat(64))
        }
        File(packDirectory, "metadata").mkdirs()
        File(packDirectory, "metadata/cover_index.json").writeText(
            JSONObject().put("schemaVersion", 1).put("covers", indexRows).toString()
        )
        val bundled = (1..100).map { index ->
            item(MediaType.MOVIE, "fixture-$index", "tmdb", "/fixture-$index.png")
        }
        val additional = (101..175).map { index ->
            item(MediaType.MOVIE, "fixture-$index", "tmdb", "/fixture-$index.png")
        }
        val otherPack = item(MediaType.MANGA, "other-pack", "mangadex", "other.png")
        mediaRepository.upsertItems(bundled + additional + otherPack)
        mediaRepository.saveSimpleRating(bundled.first().id, RatingDraft(), 9.0, 9.0)
        val manifest = PackManifest(
            schemaVersion = 1,
            packId = OfficialPackIds.Movies,
            displayName = "Movies",
            version = "fixture",
            minRankOfflineVersion = "0.2.0",
            provider = PackProvider("tmdb", "TMDB"),
            coverSources = emptyList(),
            catalog = listOf("catalog/fixture.jsonl.gzip"),
            groups = emptyList(),
            covers = coverFiles.map(PackManifestFile::path),
            capabilities = PackCapabilities(true, true, true, true, false, true),
            filters = emptyList(),
            subtitle = emptyList(),
            merge = PackMergeDefinition(false, PackMergeStrategy.NONE, null),
            defaults = PackDefaults(true, false, false, "simple"),
            files = coverFiles
        )
        mediaRepository.installPackCovers(manifest, packDirectory)
        val downloader = FakeDownloader()
        val covers = GenericMediaCoverRepository(context, database, downloader = downloader)

        assertEquals(100, covers.observeDownloadedCoverCount(MediaType.MOVIE).first())
        val progress = CoverPreloadManager(covers).preloadTop(
            MediaType.MOVIE,
            limit = 175,
            oneCoverPerGroup = false
        )
        assertEquals(75, downloader.invocations.get())
        assertEquals(100, progress.existing)
        assertEquals(75, progress.downloaded)
        assertEquals(175, progress.processed)
        assertEquals(175, covers.observeDownloadedCoverCount(MediaType.MOVIE).first())
        assertEquals(CoverDownloadResult.Downloaded, covers.downloadCover(otherPack.id))

        assertEquals(175, covers.clearDownloadedCovers(MediaType.MOVIE))
        assertTrue(covers.clearDownloadedCoverFiles(MediaType.MOVIE))
        assertEquals(0, covers.observeDownloadedCoverCount(MediaType.MOVIE).first())
        assertEquals(1, covers.observeDownloadedCoverCount(MediaType.MANGA).first())
        assertEquals(9.0, mediaRepository.rating(bundled.first().id)!!.finalScore!!, 0.0)

        // Startup recovery sees the retained index but never recreates user-deleted image files.
        mediaRepository.installPackCovers(manifest, packDirectory)
        assertEquals(0, covers.observeDownloadedCoverCount(MediaType.MOVIE).first())
        assertFalse(coverDirectory.listFiles().orEmpty().any(File::isFile))
    }

    @Test
    fun declarativeCustomProviderDownloadsWithoutProviderSpecificRepositoryCode() = runBlocking {
        val custom = item(MediaType.MOVIE, "custom-cover", "custom", "cover one.webp")
        mediaRepository.upsertItem(custom)
        val manifest = PackManifest(
            schemaVersion = 1,
            packId = OfficialPackIds.Movies,
            displayName = "Custom Movies",
            version = "fixture",
            minRankOfflineVersion = "0.2.0",
            provider = PackProvider("custom", "Fixture provider"),
            coverSources = listOf(PackCoverSource(
                id = "fixture_images",
                providerId = "custom",
                urlTemplates = listOf("https://images.example.org/posters/{providerId}/{reference}"),
                allowedHosts = setOf("images.example.org"),
                attributionName = "Fixture provider",
                attributionUrl = "https://example.org"
            )),
            catalog = listOf("catalog/items.jsonl.gzip"),
            groups = emptyList(),
            covers = emptyList(),
            capabilities = PackCapabilities(true, true, true, true, false, true),
            filters = emptyList(),
            subtitle = emptyList(),
            merge = PackMergeDefinition(false, PackMergeStrategy.NONE, null),
            defaults = PackDefaults(true, false, false, "simple"),
            files = listOf(PackManifestFile("catalog/items.jsonl.gzip", 1, "0".repeat(64)))
        )
        val downloader = FakeDownloader()
        val covers = GenericMediaCoverRepository(
            context,
            database,
            downloader = downloader,
            manifestProvider = { manifest }
        )

        assertEquals(CoverDownloadResult.Downloaded, covers.downloadCover(custom.id))
        assertEquals(
            listOf("https://images.example.org/posters/custom-cover/cover%20one.webp"),
            downloader.uris.map(URI::toASCIIString)
        )
        assertEquals(1, covers.observeDownloadedCoverCount(MediaType.MOVIE).first())
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
        assertEquals(9.0, database.mediaGroupDao().groupRating("movies", group.id)!!.finalScore!!, 0.0)
        assertEquals(listOf(first.id, second.id), database.mediaGroupDao().groupMembers(group.id).map { it.id })
    }

    @Test
    fun clearingCoversIsPackScopedAndBundledCoversAreAlsoRemovable() = runBlocking {
        val downloader = FakeDownloader()
        val covers = GenericMediaCoverRepository(context, database, downloader = downloader)
        val groups = GenericMediaGroupCoverRepository(context, database, downloader = FakeDownloader())
        val anime = item(MediaType.ANIME, "anime", "anime_offline_database", null)
            .copy(popularityRank = 1)
        val manga = item(MediaType.MANGA, "manga", "mangadex", "cover.png")
            .copy(popularityRank = 1)
        val seriesMember = item(MediaType.SERIES, "season", "tmdb", "/season.jpg")
            .copy(popularityRank = 1)
        mediaRepository.upsertItems(listOf(anime, manga, seriesMember))
        mediaRepository.saveSimpleRating(anime.id, RatingDraft(), 8.5, 8.5)
        assertEquals(CoverDownloadResult.Downloaded, covers.downloadCover(manga.id))
        assertEquals(CoverDownloadResult.Downloaded, covers.downloadCover(seriesMember.id))

        val bundled = File(context.filesDir, "packs/animes/covers/bundled.png").apply {
            parentFile!!.mkdirs()
            FakeDownloader().download(URI("https://cdn.myanimelist.net/bundled.png"), this)
        }
        database.mediaDao().updateMediaCover(
            anime.id,
            bundled.absolutePath,
            CoverResolutionState.AVAILABLE.name
        )
        downloader.invocations.set(0)

        assertEquals(1, covers.observeDownloadedCoverCount(MediaType.ANIME).first())
        assertEquals(CoverDownloadResult.AlreadyAvailable, covers.downloadCover(anime.id))
        assertEquals(0, downloader.invocations.get())
        assertEquals(1, covers.observeDownloadedCoverCount(MediaType.MANGA).first())
        assertEquals(1, covers.clearDownloadedCovers(MediaType.MANGA))
        assertEquals(0, groups.clearDownloadedCovers(MediaType.MANGA))

        assertTrue(bundled.isFile)
        assertNotNull(database.mediaDao().item(anime.id)!!.localCoverPath)
        assertNotNull(database.mediaDao().item(seriesMember.id)!!.localCoverPath)
        assertNull(database.mediaDao().item(manga.id)!!.localCoverPath)

        assertEquals(1, covers.clearDownloadedCovers(MediaType.ANIME))
        assertTrue(covers.clearDownloadedCoverFiles(MediaType.ANIME))
        assertFalse(bundled.exists())
        assertNull(database.mediaDao().item(anime.id)!!.localCoverPath)
        assertEquals(0, covers.observeDownloadedCoverCount(MediaType.ANIME).first())
        assertNotNull(database.mediaDao().item(seriesMember.id)!!.localCoverPath)
        assertEquals(8.5, mediaRepository.rating(anime.id)!!.finalScore!!, 0.0)
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
        val uris = java.util.concurrent.CopyOnWriteArrayList<URI>()

        override suspend fun download(remoteUri: URI, destination: File): MediaCoverTransferResult {
            invocations.incrementAndGet()
            uris += remoteUri
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            return MediaCoverTransferResult.DOWNLOADED
        }
    }

    private class ScriptedDownloader(
        private val results: Map<String, List<MediaCoverTransferResult>>
    ) : MediaCoverDownloader {
        private val invocationCounts = ConcurrentHashMap<String, AtomicInteger>()

        fun attempts(key: String): Int = invocationCounts[key]?.get() ?: 0

        override suspend fun download(remoteUri: URI, destination: File): MediaCoverTransferResult {
            val key = results.keys.first { remoteUri.path.contains(it) }
            val attempt = invocationCounts.getOrPut(key) { AtomicInteger() }.getAndIncrement()
            val result = results.getValue(key).getOrElse(attempt) { results.getValue(key).last() }
            if (result == MediaCoverTransferResult.DOWNLOADED) {
                val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            return result
        }
    }

    private companion object {
        const val TEST_MANGADEX_ID = "00000000-0000-0000-0000-000000000001"
    }
}
