package org.rankoffline.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryPackManagerTest {
    private val context = IsolatedTestContext(
        InstrumentationRegistry.getInstrumentation().targetContext,
        "category-pack-manager"
    )

    @After
    fun tearDown() = context.delete()

    @Test
    fun queuedDownloadsShareProgressAndInstallOnlyValidatedPacks() = runBlocking {
        val root = File(context.filesDir, CategoryPackManager.PACK_ROOT)
        root.deleteRecursively()
        val manga = fixture(MediaType.MANGA, "manga_catalog.jsonl.gzip", "manga fixture".toByteArray())
        val book = fixture(MediaType.BOOK, "book_catalog.jsonl.gzip", "book fixture".toByteArray())
        var active = 0
        var maxActive = 0
        val downloader = CategoryPackArchiveDownloader { descriptor, destination, progress ->
            active++
            maxActive = maxOf(maxActive, active)
            try {
                val bytes = if (descriptor.installManifest.packId == OfficialPackIds.Manga) manga.archive else book.archive
                val half = bytes.size / 2
                destination.outputStream().use { output ->
                    output.write(bytes, 0, half)
                    progress(half.toLong())
                    delay(30)
                    output.write(bytes, half, bytes.size - half)
                    progress(bytes.size.toLong())
                }
                CategoryPackDownloadResult.INSTALLED
            } finally {
                active--
            }
        }
        val manager = CategoryPackManager(
            context = context,
            injectedDownloader = downloader,
            injectedDescriptors = mapOf(
                OfficialPackIds.Manga to manga.descriptor,
                OfficialPackIds.Books to book.descriptor
            ),
            bundledCatalogsAvailable = false
        )

        val first = async { manager.downloadAndInstall(MediaType.MANGA) }
        val second = async { manager.downloadAndInstall(MediaType.BOOK) }
        val combined = manager.downloadProgress.filterNotNull().first {
            it.totalBytes == manga.archive.size.toLong() + book.archive.size.toLong() &&
                it.currentMediaType != null
        }

        assertEquals(CategoryPackDownloadResult.INSTALLED, first.await())
        assertEquals(CategoryPackDownloadResult.INSTALLED, second.await())
        assertEquals(1, maxActive)
        assertEquals(manga.archive.size.toLong() + book.archive.size.toLong(), combined.totalBytes)
        assertEquals(2, combined.transferCount)
        assertEquals(1, combined.currentPosition)
        assertTrue(combined.currentMediaType == MediaType.MANGA || combined.currentMediaType == MediaType.BOOK)
        assertArrayEquals(
            manga.payload,
            File(
                requireNotNull(manager.installedDirectory(MediaType.MANGA)),
                "catalog/manga_catalog.jsonl.gzip"
            ).readBytes()
        )
        assertTrue(manager.states.value.getValue(MediaType.MANGA).available)
        assertTrue(manager.states.value.getValue(MediaType.MANGA).reclaimableBytes > 0L)
        assertArrayEquals(
            manga.archive,
            File(
                File(context.filesDir, CategoryPackManager.PACK_ARCHIVE_ROOT),
                manga.descriptor.archiveName
            ).readBytes()
        )
        assertArrayEquals(
            book.archive,
            File(
                File(context.filesDir, CategoryPackManager.PACK_ARCHIVE_ROOT),
                book.descriptor.archiveName
            ).readBytes()
        )
        root.deleteRecursively()
        Unit
    }

    @Test
    fun fiveArchivesStaySequentialButContinueWhileRoomImportIsBusy() = runBlocking {
        val fixtures = mapOf(
            MediaType.ANIME to fixture(MediaType.ANIME, "anime_catalog.jsonl.gzip", "anime".toByteArray()),
            MediaType.MANGA to fixture(MediaType.MANGA, "manga_catalog.jsonl.gzip", "manga".toByteArray()),
            MediaType.MOVIE to fixture(MediaType.MOVIE, "movie_catalog.jsonl.gzip", "movie".toByteArray()),
            MediaType.SERIES to fixture(MediaType.SERIES, "series_catalog.jsonl.gzip", "series".toByteArray()),
            MediaType.BOOK to fixture(MediaType.BOOK, "book_catalog.jsonl.gzip", "book".toByteArray())
        )
        val fixtureByPack = fixtures.values.associateBy { it.descriptor.manifest.packId }
        val firstDownloadStarted = CompletableDeferred<Unit>()
        val releaseFirstDownload = CompletableDeferred<Unit>()
        val firstImportStarted = CompletableDeferred<Unit>()
        val releaseFirstImport = CompletableDeferred<Unit>()
        val allDownloadsFinished = CompletableDeferred<Unit>()
        val activeDownloads = AtomicInteger(0)
        val maxDownloads = AtomicInteger(0)
        val completedDownloads = AtomicInteger(0)
        val activeImports = AtomicInteger(0)
        val maxImports = AtomicInteger(0)
        val downloader = CategoryPackArchiveDownloader { descriptor, destination, progress ->
            val nowActive = activeDownloads.incrementAndGet()
            maxDownloads.updateAndGet { maxOf(it, nowActive) }
            try {
                val fixture = fixtureByPack.getValue(descriptor.installManifest.packId)
                destination.writeBytes(fixture.archive)
                progress(fixture.archive.size.toLong())
                if (descriptor.installManifest.packId == OfficialPackIds.Anime) {
                    firstDownloadStarted.complete(Unit)
                    releaseFirstDownload.await()
                }
                if (completedDownloads.incrementAndGet() == MediaType.entries.size) {
                    allDownloadsFinished.complete(Unit)
                }
                CategoryPackDownloadResult.INSTALLED
            } finally {
                activeDownloads.decrementAndGet()
            }
        }
        val manager = CategoryPackManager(
            context = context,
            injectedDownloader = downloader,
            injectedDescriptors = fixtureByPack.mapValues { it.value.descriptor },
            bundledCatalogsAvailable = false
        )

        fun install(mediaType: MediaType) = async {
            manager.downloadAndInstall(
                mediaType = mediaType,
                importInstalledPack = { phase ->
                    phase(CategoryPackInstallPhase.IMPORTING_CATALOG)
                    val active = activeImports.incrementAndGet()
                    maxImports.updateAndGet { maxOf(it, active) }
                    try {
                        if (mediaType == MediaType.ANIME) {
                            firstImportStarted.complete(Unit)
                            releaseFirstImport.await()
                        } else {
                            delay(10)
                        }
                        true
                    } finally {
                        activeImports.decrementAndGet()
                    }
                }
            )
        }

        val anime = install(MediaType.ANIME)
        withTimeout(5_000) { firstDownloadStarted.await() }
        val remaining = MediaType.entries.filterNot { it == MediaType.ANIME }.map(::install)
        withTimeout(5_000) {
            manager.states.first {
                it[MediaType.MANGA]?.installProgress?.phase ==
                    CategoryPackInstallPhase.WAITING_DOWNLOAD
            }
        }
        releaseFirstDownload.complete(Unit)
        withTimeout(5_000) { firstImportStarted.await() }
        withTimeout(5_000) { allDownloadsFinished.await() }

        assertEquals(1, maxDownloads.get())
        assertEquals(1, activeImports.get())
        val queuedForImport = withTimeout(5_000) {
            manager.states.first { states ->
                states[MediaType.ANIME]?.installProgress?.phase ==
                    CategoryPackInstallPhase.IMPORTING_CATALOG &&
                    MediaType.entries.filterNot { it == MediaType.ANIME }.all { mediaType ->
                        states[mediaType]?.installProgress?.phase ==
                            CategoryPackInstallPhase.WAITING_IMPORT
                    }
            }
        }
        assertEquals(
            CategoryPackInstallPhase.IMPORTING_CATALOG,
            queuedForImport.getValue(MediaType.ANIME).installProgress?.phase
        )
        assertTrue(
            MediaType.entries.filterNot { it == MediaType.ANIME }.all { mediaType ->
                queuedForImport.getValue(mediaType).installProgress?.phase ==
                    CategoryPackInstallPhase.WAITING_IMPORT
            }
        )
        releaseFirstImport.complete(Unit)
        assertEquals(
            List(MediaType.entries.size) { CategoryPackDownloadResult.INSTALLED },
            listOf(anime.await()) + remaining.map { it.await() }
        )
        assertEquals(1, maxImports.get())
        assertTrue(MediaType.entries.all { manager.states.value.getValue(it).available })
    }

    @Test
    fun corruptDownloadNeverBecomesActive() = runBlocking {
        val root = File(context.filesDir, CategoryPackManager.PACK_ROOT)
        root.deleteRecursively()
        val fixture = fixture(MediaType.BOOK, "book_catalog.jsonl.gzip", "valid".toByteArray())
        val manager = CategoryPackManager(
            context = context,
            injectedDownloader = CategoryPackArchiveDownloader { _, destination, progress ->
                destination.writeBytes("not a zip".toByteArray())
                progress(destination.length())
                CategoryPackDownloadResult.INSTALLED
            },
            injectedDescriptors = mapOf(OfficialPackIds.Books to fixture.descriptor),
            bundledCatalogsAvailable = false
        )

        assertEquals(CategoryPackDownloadResult.INVALID_PACK, manager.downloadAndInstall(MediaType.BOOK))
        assertFalse(manager.states.value.getValue(MediaType.BOOK).available)
        assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
    }

    @Test
    fun catalogIsReadyAndSelectableBeforeInitialCoversFinish() = runBlocking {
        val fixture = fixture(MediaType.MANGA, "manga_catalog.jsonl.gzip", "manga fixture".toByteArray())
        val manager = managerFor(fixture)
        var importFinished = false
        var catalogReadyCallbackRan = false
        var coverCallbackRan = false

        assertEquals(
            CategoryPackDownloadResult.INSTALLED,
            manager.downloadAndInstall(
                mediaType = MediaType.MANGA,
                importInstalledPack = { phase ->
                    phase(CategoryPackInstallPhase.IMPORTING_CATALOG)
                    importFinished = true
                    true
                },
                onCatalogReady = {
                    catalogReadyCallbackRan = true
                    assertTrue(manager.states.value.getValue(MediaType.MANGA).available)
                },
                downloadInitialCovers = true,
                acquireInitialCovers = { report ->
                    assertTrue(importFinished)
                    assertTrue(catalogReadyCallbackRan)
                    coverCallbackRan = true
                    report(CoverPreloadProgress(
                        mediaType = MediaType.MANGA,
                        status = CoverPreloadStatus.DOWNLOADING_COVERS,
                        target = 100,
                        processed = 20,
                        existing = 20
                    ))
                    val installing = manager.states.value.getValue(MediaType.MANGA)
                    assertTrue(installing.available)
                    assertFalse(installing.canDelete)
                    assertEquals(
                        CategoryPackInstallPhase.DOWNLOADING_COVERS,
                        installing.installProgress?.phase
                    )
                    CoverPreloadProgress(
                        mediaType = MediaType.MANGA,
                        status = CoverPreloadStatus.COMPLETED,
                        target = 100,
                        processed = 100,
                        existing = 20,
                        downloaded = 80
                    )
                }
            )
        )

        assertTrue(catalogReadyCallbackRan)
        assertTrue(coverCallbackRan)
        val ready = manager.states.value.getValue(MediaType.MANGA)
        assertTrue(ready.available)
        assertEquals(InitialCoverAcquisitionStatus.COMPLETED, ready.initialCoverAcquisition?.status)
        assertEquals(100, ready.initialCoverAcquisition?.available)

        val restarted = CategoryPackManager(
            context,
            injectedDownloader = CategoryPackArchiveDownloader { _, _, _ ->
                throw AssertionError("Restart must not redownload initial covers")
            },
            injectedDescriptors = mapOf(OfficialPackIds.Manga to fixture.descriptor),
            bundledCatalogsAvailable = false
        )
        restarted.recoverFilesystem()
        restarted.refresh()
        assertEquals(
            InitialCoverAcquisitionStatus.COMPLETED,
            restarted.states.value.getValue(MediaType.MANGA).initialCoverAcquisition?.status
        )
    }

    @Test
    fun showImagesOffSkipsCoverRequestsAndPartialOrCancelledCoversKeepCatalog() = runBlocking {
        val book = fixture(MediaType.BOOK, "book_catalog.jsonl.gzip", "book fixture".toByteArray())
        val skipped = managerFor(book)
        var coverCalls = 0
        assertEquals(
            CategoryPackDownloadResult.INSTALLED,
            skipped.downloadAndInstall(
                mediaType = MediaType.BOOK,
                downloadInitialCovers = false,
                acquireInitialCovers = {
                    coverCalls++
                    error("Show Images is disabled")
                }
            )
        )
        assertEquals(0, coverCalls)
        assertTrue(skipped.states.value.getValue(MediaType.BOOK).available)
        assertEquals(
            InitialCoverAcquisitionStatus.SKIPPED,
            skipped.states.value.getValue(MediaType.BOOK).initialCoverAcquisition?.status
        )

        val movie = fixture(MediaType.MOVIE, "movie_catalog.jsonl.gzip", "movie fixture".toByteArray())
        val partial = managerFor(movie)
        assertEquals(
            CategoryPackDownloadResult.INSTALLED,
            partial.downloadAndInstall(
                mediaType = MediaType.MOVIE,
                downloadInitialCovers = true,
                acquireInitialCovers = {
                    CoverPreloadProgress(
                        mediaType = MediaType.MOVIE,
                        status = CoverPreloadStatus.COMPLETED,
                        target = 100,
                        processed = 100,
                        downloaded = 84,
                        unavailable = 12,
                        errors = 4
                    )
                }
            )
        )
        val partialState = partial.states.value.getValue(MediaType.MOVIE)
        assertTrue(partialState.available)
        assertEquals(InitialCoverAcquisitionStatus.PARTIAL, partialState.initialCoverAcquisition?.status)
        assertEquals(84, partialState.initialCoverAcquisition?.available)

        val anime = fixture(MediaType.ANIME, "anime_catalog.jsonl.gzip", "anime fixture".toByteArray())
        val cancelled = managerFor(anime)
        val interrupted = runCatching {
            cancelled.downloadAndInstall(
                mediaType = MediaType.ANIME,
                downloadInitialCovers = true,
                acquireInitialCovers = { report ->
                    report(CoverPreloadProgress(
                        mediaType = MediaType.ANIME,
                        status = CoverPreloadStatus.DOWNLOADING_COVERS,
                        target = 100,
                        processed = 35,
                        downloaded = 35
                    ))
                    throw CancellationException("fixture cancellation")
                }
            )
        }
        assertTrue(interrupted.exceptionOrNull() is CancellationException)
        val cancelledState = cancelled.states.value.getValue(MediaType.ANIME)
        assertTrue(cancelledState.available)
        assertTrue(requireNotNull(cancelled.installedDirectory(MediaType.ANIME)).isDirectory)
        assertEquals(
            InitialCoverAcquisitionStatus.PENDING,
            cancelledState.initialCoverAcquisition?.status
        )
        assertEquals(35, cancelledState.initialCoverAcquisition?.available)
        cancelled.requestInitialCoverCancellation(MediaType.ANIME)
        assertEquals(
            InitialCoverAcquisitionStatus.CANCELLED,
            cancelled.states.value.getValue(MediaType.ANIME).initialCoverAcquisition?.status
        )
    }

    @Test
    fun purgeWaitsForActiveOptionalCoverPhaseWithoutHidingReadyCatalog() = runBlocking {
        val fixture = fixture(MediaType.SERIES, "series_catalog.jsonl.gzip", "series fixture".toByteArray())
        val manager = managerFor(fixture)
        val coversStarted = CompletableDeferred<Unit>()
        val releaseCovers = CompletableDeferred<Unit>()
        var purgeStarted = false
        val install = async {
            manager.downloadAndInstall(
                mediaType = MediaType.SERIES,
                downloadInitialCovers = true,
                acquireInitialCovers = { report ->
                    report(CoverPreloadProgress(
                        mediaType = MediaType.SERIES,
                        status = CoverPreloadStatus.DOWNLOADING_COVERS,
                        target = 100,
                        processed = 1,
                        downloaded = 1
                    ))
                    coversStarted.complete(Unit)
                    releaseCovers.await()
                    CoverPreloadProgress(
                        mediaType = MediaType.SERIES,
                        status = CoverPreloadStatus.COMPLETED,
                        target = 100,
                        processed = 100,
                        downloaded = 100
                    )
                }
            )
        }
        coversStarted.await()
        assertTrue(manager.states.value.getValue(MediaType.SERIES).available)

        val purge = async {
            manager.purgePack(
                MediaType.SERIES,
                purgeRuntimeData = { purgeStarted = true },
                finalizeRuntime = {}
            )
        }
        delay(50)
        assertFalse(purgeStarted)
        releaseCovers.complete(Unit)
        assertEquals(CategoryPackDownloadResult.INSTALLED, install.await())
        assertTrue(purge.await())
        assertTrue(purgeStarted)
    }

    @Test
    fun filesystemAloneIsNotReadyAndStartupRecoveryPublishesReadyAfterImport() = runBlocking {
        File(context.filesDir, CategoryPackManager.PACK_ROOT).deleteRecursively()
        val fixture = fixture(MediaType.BOOK, "book_catalog.jsonl.gzip", "book fixture".toByteArray())
        val downloader = CategoryPackArchiveDownloader { _, destination, progress ->
            destination.writeBytes(fixture.archive)
            progress(fixture.archive.size.toLong())
            CategoryPackDownloadResult.INSTALLED
        }
        val first = CategoryPackManager(
            context,
            injectedDownloader = downloader,
            injectedDescriptors = mapOf(OfficialPackIds.Books to fixture.descriptor),
            bundledCatalogsAvailable = false
        )
        assertEquals(CategoryPackDownloadResult.INSTALLED, first.downloadAndInstall(MediaType.BOOK))

        val recovered = CategoryPackManager(
            context,
            injectedDownloader = downloader,
            injectedDescriptors = mapOf(OfficialPackIds.Books to fixture.descriptor),
            bundledCatalogsAvailable = false
        )
        recovered.recoverFilesystem()
        recovered.refresh()
        assertFalse(recovered.states.value.getValue(MediaType.BOOK).available)
        assertEquals(
            CatalogAvailability.AVAILABLE,
            recovered.recoverRuntime(
                MediaType.BOOK,
                clearMissingCatalog = {},
                initializeRuntime = { phase ->
                    phase(CategoryPackInstallPhase.IMPORTING_CATALOG)
                    CatalogAvailability.AVAILABLE
                }
            )
        )
        assertTrue(recovered.states.value.getValue(MediaType.BOOK).available)
    }

    @Test
    fun cancelledRecoveryDoesNotLeaveWaitingImportProgressBehind() = runBlocking {
        File(context.filesDir, CategoryPackManager.PACK_ROOT).deleteRecursively()
        val fixture = fixture(MediaType.BOOK, "book_catalog.jsonl.gzip", "book fixture".toByteArray())
        val downloader = CategoryPackArchiveDownloader { _, destination, progress ->
            destination.writeBytes(fixture.archive)
            progress(fixture.archive.size.toLong())
            CategoryPackDownloadResult.INSTALLED
        }
        val descriptors = mapOf(OfficialPackIds.Books to fixture.descriptor)
        val installed = CategoryPackManager(
            context,
            injectedDownloader = downloader,
            injectedDescriptors = descriptors,
            bundledCatalogsAvailable = false
        )
        assertEquals(CategoryPackDownloadResult.INSTALLED, installed.downloadAndInstall(MediaType.BOOK))

        val recovered = CategoryPackManager(
            context,
            injectedDownloader = downloader,
            injectedDescriptors = descriptors,
            bundledCatalogsAvailable = false
        )
        recovered.recoverFilesystem()
        recovered.refresh()
        val importStarted = CompletableDeferred<Unit>()
        val keepImportOpen = CompletableDeferred<Unit>()
        val recovery = async {
            recovered.recoverRuntime(
                MediaType.BOOK,
                clearMissingCatalog = {},
                initializeRuntime = { phase ->
                    phase(CategoryPackInstallPhase.IMPORTING_CATALOG)
                    importStarted.complete(Unit)
                    keepImportOpen.await()
                    CatalogAvailability.AVAILABLE
                }
            )
        }
        importStarted.await()
        recovery.cancel()
        recovery.join()

        val state = recovered.states.value.getValue(MediaType.BOOK)
        assertEquals(null, state.installProgress)
        assertEquals(CategoryPackRuntimeStatus.FILES_INSTALLED, state.runtimeStatus)
    }

    @Test
    fun cancellationAfterPromotionRestoresPreviousFilesAndRuntime() = runBlocking {
        File(context.filesDir, CategoryPackManager.PACK_ROOT).deleteRecursively()
        val original = fixture(MediaType.BOOK, "book_catalog.jsonl.gzip", "original".toByteArray())
        val originalManager = managerFor(original)
        assertEquals(
            CategoryPackDownloadResult.INSTALLED,
            originalManager.downloadAndInstall(MediaType.BOOK)
        )
        val replacement = fixture(
            MediaType.BOOK,
            "book_catalog.jsonl.gzip",
            "replacement".toByteArray()
        )
        val replacing = managerFor(replacement)
        var importCalls = 0
        val operation = async {
            replacing.downloadAndInstall(
                MediaType.BOOK,
                importInstalledPack = { phase ->
                    importCalls++
                    phase(CategoryPackInstallPhase.IMPORTING_CATALOG)
                    if (importCalls == 1) throw CancellationException("cancel replacement")
                    true
                }
            )
        }

        runCatching { operation.await() }

        assertEquals(2, importCalls)
        assertArrayEquals(
            original.payload,
            File(
                requireNotNull(replacing.installedDirectory(MediaType.BOOK)),
                "catalog/book_catalog.jsonl.gzip"
            ).readBytes()
        )
        assertTrue(replacing.states.value.getValue(MediaType.BOOK).available)
    }

    @Test
    fun purgeOfSamePackWaitsForInstallLifecycle() = runBlocking {
        File(context.filesDir, CategoryPackManager.PACK_ROOT).deleteRecursively()
        val fixture = fixture(MediaType.BOOK, "book_catalog.jsonl.gzip", "book fixture".toByteArray())
        val downloadEntered = CompletableDeferred<Unit>()
        val releaseDownload = CompletableDeferred<Unit>()
        val purgeEntered = CompletableDeferred<Unit>()
        val releasePurge = CompletableDeferred<Unit>()
        var purgeRuntimeStarted = false
        val manager = CategoryPackManager(
            context,
            injectedDownloader = CategoryPackArchiveDownloader { _, destination, progress ->
                downloadEntered.complete(Unit)
                releaseDownload.await()
                destination.writeBytes(fixture.archive)
                progress(fixture.archive.size.toLong())
                CategoryPackDownloadResult.INSTALLED
            },
            injectedDescriptors = mapOf(OfficialPackIds.Books to fixture.descriptor),
            bundledCatalogsAvailable = false
        )

        val install = async { manager.downloadAndInstall(MediaType.BOOK) }
        downloadEntered.await()
        val purge = async {
            manager.purgePack(
                MediaType.BOOK,
                purgeRuntimeData = {
                    purgeRuntimeStarted = true
                    purgeEntered.complete(Unit)
                    releasePurge.await()
                },
                finalizeRuntime = {}
            )
        }
        delay(50)
        assertFalse(purgeRuntimeStarted)
        releaseDownload.complete(Unit)
        assertEquals(CategoryPackDownloadResult.INSTALLED, install.await())
        purgeEntered.await()
        val deleting = manager.states.value.getValue(MediaType.BOOK)
        assertEquals(CategoryPackRuntimeStatus.PURGING, deleting.runtimeStatus)
        assertFalse(deleting.available)
        assertFalse(deleting.canDelete)
        releasePurge.complete(Unit)
        assertTrue(purge.await())
        assertTrue(purgeRuntimeStarted)
        assertFalse(manager.states.value.getValue(MediaType.BOOK).available)
        assertFalse(
            File(
                File(context.filesDir, CategoryPackManager.PACK_ARCHIVE_ROOT),
                fixture.descriptor.archiveName
            ).exists()
        )
    }

    private fun fixture(mediaType: MediaType, fileName: String, payload: ByteArray): PackFixture {
        val packId = mediaType.toPackId()
        val packPath = "catalog/$fileName"
        val payloadSha = payload.sha256()
        val encodedManifest = """{"schemaVersion":1,"packId":"${packId.value}","displayName":"${mediaType.name}","version":"test","minRankOfflineVersion":"0.2.0","provider":{"id":"test","name":"Test"},"catalog":["$packPath"],"groups":[],"covers":[],"capabilities":{"search":true,"filters":true,"covers":true,"popularity":true,"merge":false,"ratings":true},"filters":[{"id":"genre","field":"genre","type":"multi_select","order":1}],"subtitle":["genre"],"merge":{"enabled":false,"strategy":"NONE"},"defaults":{"showImages":true,"stickyScoreHeader":false,"mergeEnabled":false,"ratingMode":"simple"},"integrity":{"installedBytes":${payload.size},"files":[{"path":"$packPath","bytes":${payload.size},"sha256":"$payloadSha"}]}}"""
        val manifest = PackManifestParser.parse(encodedManifest)
        val archive = ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry(packPath))
                zip.write(payload)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(CategoryPackInstaller.PACK_MANIFEST))
                zip.write(encodedManifest.toByteArray())
                zip.closeEntry()
            }
            bytes.toByteArray()
        }
        val descriptor = CategoryPackDescriptor(
            installManifest = CategoryPackInstallManifest(
                packId = packId,
                snapshot = "test-${UUID.randomUUID()}",
                downloadBytes = archive.size.toLong(),
                installedBytes = payload.size.toLong(),
                files = listOf(CategoryPackFileSpec(packPath, payload.size.toLong(), payloadSha)),
                encodedManifest = encodedManifest
            ),
            manifest = manifest,
            formatVersion = 1,
            minimumDatabaseVersion = ROOM_DATABASE_VERSION,
            archiveName = "$mediaType.zip",
            archiveBytes = archive.size.toLong(),
            archiveSha256 = archive.sha256()
        )
        return PackFixture(payload, archive, descriptor)
    }

    private fun managerFor(fixture: PackFixture) = CategoryPackManager(
        context = context,
        injectedDownloader = CategoryPackArchiveDownloader { _, destination, progress ->
            destination.writeBytes(fixture.archive)
            progress(fixture.archive.size.toLong())
            CategoryPackDownloadResult.INSTALLED
        },
        injectedDescriptors = mapOf(fixture.descriptor.manifest.packId to fixture.descriptor),
        bundledCatalogsAvailable = false
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private data class PackFixture(
        val payload: ByteArray,
        val archive: ByteArray,
        val descriptor: CategoryPackDescriptor
    )
}
