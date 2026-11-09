package org.rankoffline.app

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, isolated ten-image sample; exercises real declarative sources without bulk traffic. */
@RunWith(AndroidJUnit4::class)
class RealCoverDownloadInstrumentedTest {
    @Test fun installedAnimeCanDownloadOneCoverFromItsDeclaredSource() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Use an isolated emulator", Build.HARDWARE.startsWith("ranchu") ||
            Build.HARDWARE.startsWith("goldfish"))
        val input = requireNotNull(app.getExternalFilesDir(null))
        assumeTrue("Explicit network opt-in required",
            File(input, "rankoffline-benchmark-covers.flag").isFile)
        val manifestFile = File(app.filesDir, "packs/pack-1/manifest.json")
        assumeTrue("Anime must be installed in this emulator", manifestFile.isFile)
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        assumeTrue("Validated Internet required", network != null &&
            connectivity.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        val manifest = PackManifestParser.parse(manifestFile.readText())
        val database = Room.databaseBuilder(app, RankOfflineDatabase::class.java,
            "rankoffline_universal.db").setJournalMode(
            androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        try {
            val repository = CoverRepository(app, database,
                manifestProvider = { id -> manifest.takeIf { it.packId == id } },
                beforeCoverMutation = { _, _ -> },
                downloader = SecureCoverDownloader(PackOperationScheduler()))
            val keys = repository.topPopularVisibleCoverKeys(manifest.packId, 20, false)
            var key: CatalogEntryKey? = null
            for (candidate in keys) {
                if (repository.observeCover(candidate).first().localPath == null) {
                    key = candidate
                    break
                }
            }
            val selected = requireNotNull(key) { "The sample already has all 20 local covers" }
            val started = SystemClock.elapsedRealtime()
            val result = withTimeout(60_000) { repository.downloadCover(selected) }
            Log.i("RankOfflineRealCovers", "pack=${manifest.packId.value} sample=1 " +
                "durationMs=${SystemClock.elapsedRealtime() - started} result=$result")
            assertTrue("The declared source should return a real cover: $result",
                result == CoverDownloadResult.Downloaded ||
                    result == CoverDownloadResult.AlreadyAvailable)
        } finally { database.close() }
    }

    @Test fun realCoverHttpCompletesWhileAnotherCatalogImports() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Use an isolated emulator", Build.HARDWARE.startsWith("ranchu") ||
            Build.HARDWARE.startsWith("goldfish"))
        val input = requireNotNull(app.getExternalFilesDir(null))
        val anime = File(input, "rankoffline-test-pack-a.pack")
        val manga = File(input, "rankoffline-test-pack-b.pack")
        assumeTrue("Verified fixtures and explicit opt-in flag required", anime.isFile && manga.isFile &&
            File(input, "rankoffline-benchmark-covers.flag").isFile)
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        assumeTrue("Validated Internet required", network != null &&
            connectivity.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)

        val root = File(app.cacheDir, "cover-during-import-test")
        val databaseName = "cover-during-import-test.db"
        app.deleteDatabase(databaseName)
        root.deleteRecursively()
        check(root.mkdirs())
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getCacheDir(): File = File(root, "cache").also { it.mkdirs() }
        }
        val database = Room.databaseBuilder(app, RankOfflineDatabase::class.java, databaseName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        try {
            val installer = PackInstaller(File(root, "packs"))
            val importer = CatalogImporter(database)
            lateinit var animeManifest: PackManifest
            val animeDirectory = PackArchiveReader.withVerifiedArchive(anime, null) { request, open ->
                animeManifest = PackManifestParser.parse(request.manifestJson)
                installer.install(request, open)
            }
            importer.importIfNeeded(animeManifest, animeDirectory)
            installer.commitInstallation(animeManifest.packId)
            lateinit var mangaManifest: PackManifest
            val mangaDirectory = PackArchiveReader.withVerifiedArchive(manga, null) { request, open ->
                mangaManifest = PackManifestParser.parse(request.manifestJson)
                installer.install(request, open)
            }
            val importStart = AtomicLong()
            val importEnd = AtomicLong()
            val coverStart = AtomicLong()
            val coverEnd = AtomicLong()
            val started = CompletableDeferred<Unit>()
            val importing = async(Dispatchers.IO) {
                importStart.set(SystemClock.elapsedRealtime())
                started.complete(Unit)
                try { importer.importIfNeeded(mangaManifest, mangaDirectory) }
                finally { importEnd.set(SystemClock.elapsedRealtime()) }
            }
            try {
                withTimeout(5_000) { started.await() }
                val scheduler = PackOperationScheduler()
                val delegate = SecureCoverDownloader(scheduler)
                val downloader = CoverDownloader { uri, destination ->
                    coverStart.compareAndSet(0L, SystemClock.elapsedRealtime())
                    delegate.download(uri, destination).also { coverEnd.set(SystemClock.elapsedRealtime()) }
                }
                val covers = CoverRepository(isolated, database,
                    manifestProvider = { id -> animeManifest.takeIf { it.packId == id } },
                    beforeCoverMutation = { _, _ -> }, downloader = downloader)
                val result = withTimeout(120_000) {
                    CoverDownloadsManager(covers).downloadTop(animeManifest.packId, 1, false)
                }
                assertEquals(1, result.downloaded)
                assertTrue("Cover HTTP must begin after Manga import starts",
                    coverStart.get() >= importStart.get())
                Log.i("RankOfflineCoverOverlap", "mangaImportStart=${importStart.get()} " +
                    "coverHttp=${coverStart.get()}..${coverEnd.get()} mangaImportEnd=${importEnd.get()}")
            } finally { withTimeout(300_000) { importing.await() } }
            assertTrue("Manga import should outlast this one-cover transfer", coverEnd.get() < importEnd.get())
        } finally {
            database.close()
            app.deleteDatabase(databaseName)
            root.deleteRecursively()
        }
    }

    @Test fun sampleTenAnimeCoversWithRealNetwork() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Use an isolated emulator", Build.HARDWARE.startsWith("ranchu") ||
            Build.HARDWARE.startsWith("goldfish"))
        val input = requireNotNull(app.getExternalFilesDir(null))
        val archive = File(input, "rankoffline-test-pack-a.pack")
        assumeTrue("Verified fixture and explicit opt-in flag required", archive.isFile &&
            File(input, "rankoffline-benchmark-covers.flag").isFile)
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        assumeTrue("Validated Internet required", network != null &&
            connectivity.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)

        val root = File(app.cacheDir, "real-cover-download-test")
        val databaseName = "real-cover-download-test.db"
        app.deleteDatabase(databaseName)
        root.deleteRecursively()
        check(root.mkdirs())
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getCacheDir(): File = File(root, "cache").also { it.mkdirs() }
        }
        val database = Room.databaseBuilder(app, RankOfflineDatabase::class.java, databaseName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        try {
            val installer = PackInstaller(File(root, "packs"))
            lateinit var manifest: PackManifest
            val installed = PackArchiveReader.withVerifiedArchive(archive, null) { request, open ->
                manifest = PackManifestParser.parse(request.manifestJson)
                installer.install(request, open)
            }
            CatalogImporter(database).importIfNeeded(manifest, installed)
            installer.commitInstallation(manifest.packId)
            val scheduler = PackOperationScheduler()
            val covers = CoverRepository(isolated, database,
                manifestProvider = { id -> manifest.takeIf { it.packId == id } },
                beforeCoverMutation = { _, _ -> },
                downloader = SecureCoverDownloader(scheduler))
            val manager = CoverDownloadsManager(covers)
            val started = SystemClock.elapsedRealtime()
            var firstDownloadedAt = 0L
            val result = withTimeout(300_000) {
                manager.downloadTop(manifest.packId, 10, mergeEnabled = false) { progress ->
                    if (progress.downloaded > 0 && firstDownloadedAt == 0L) {
                        firstDownloadedAt = SystemClock.elapsedRealtime()
                    }
                }
            }
            Log.i("RankOfflineRealCovers", "pack=${manifest.packId.value} sample=10 " +
                "totalMs=${SystemClock.elapsedRealtime() - started} " +
                "firstDownloadedMs=${firstDownloadedAt.takeIf { it > 0 }?.minus(started)} " +
                "downloaded=${result.downloaded} missing=${result.unavailable} errors=${result.errors}")
            assertEquals(10, result.target)
            assertEquals(10, result.processed)
            assertTrue(result.status == CoverDownloadStatus.COMPLETED)
        } finally {
            database.close()
            app.deleteDatabase(databaseName)
            root.deleteRecursively()
        }
    }
}
