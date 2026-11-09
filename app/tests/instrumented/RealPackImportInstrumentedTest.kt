package org.rankoffline.app

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Optional real-archive regression; put two verified .pack files in externalFilesDir for this test. */
@RunWith(AndroidJUnit4::class)
class RealPackImportInstrumentedTest {
    @Test fun compareMangaOuterBatchSizesWithoutChangingTheImporter() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Run only on an isolated emulator", Build.HARDWARE.startsWith("ranchu") ||
            Build.HARDWARE.startsWith("goldfish"))
        val archive = File(context.getExternalFilesDir(null), "rankoffline-test-pack-b.pack")
        assumeTrue("Verified Manga fixture required", archive.isFile)
        val root = File(context.cacheDir, "real-manga-batch-test")
        root.deleteRecursively()
        check(root.mkdirs())
        try {
            val (manifest, directory) = install(PackInstaller(root), archive)
            for (size in listOf(400, 500)) {
                val databaseName = "real-manga-batch-$size.db"
                context.deleteDatabase(databaseName)
                val database = Room.databaseBuilder(context, RankOfflineDatabase::class.java,
                    databaseName).setJournalMode(
                    androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
                try {
                    val started = SystemClock.elapsedRealtime()
                    val result = withTimeout(300_000) {
                        CatalogImporter(database, batchSize = size).importIfNeeded(manifest, directory)
                    }
                    Log.i("RankOfflineBatchBenchmark", "pack=${manifest.packId.value} batch=$size " +
                        "totalMs=${SystemClock.elapsedRealtime() - started} result=$result")
                    assertTrue(result is CatalogImportResult.Imported)
                } finally {
                    database.close()
                    context.deleteDatabase(databaseName)
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun archiveDigestReadCostOnEmulator() { runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val input = requireNotNull(context.getExternalFilesDir(null))
        val archive = File(input, "rankoffline-test-pack-a.pack")
        val index = File(input, "rankoffline-test-pack-index.json")
        assumeTrue("Verified archive and release index are required", archive.isFile && index.isFile)
        val hosts = BuildConfig.PACK_ALLOWED_HOSTS.split(',').map(String::trim)
            .filter(String::isNotEmpty).toSet()
        val descriptor = PackIndexParser.parse(index.readText(), TrustedHttpsHosts(hosts))
            .single { it.packId == PackId(1) }
        val durations = List(3) {
            val started = SystemClock.elapsedRealtime()
            assertTrue(PackArchiveReader.verifyArchiveDigest(archive, descriptor))
            SystemClock.elapsedRealtime() - started
        }
        Log.i("RankOfflineRealImportTest", "archiveShaBytes=${archive.length()} " +
            "durationMs=$durations medianMs=${durations.sorted()[1]}")
    } }

    @Test fun freshMovieCatalogImportUsesTheRealInstaller() { runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val archive = File(context.getExternalFilesDir(null), "rankoffline-test-pack-c.pack")
        assumeTrue("Real movie pack benchmark input is absent", archive.isFile)
        val databaseName = "rankoffline-real-movie-import-test.db"
        val root = File(context.cacheDir, "real-movie-import-test")
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
        check(root.mkdirs())
        val database = Room.databaseBuilder(context, RankOfflineDatabase::class.java, databaseName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        try {
            val (manifest, directory) = install(PackInstaller(root), archive)
            val started = SystemClock.elapsedRealtime()
            val result = withTimeout(600_000) { CatalogImporter(database).importIfNeeded(manifest, directory) }
            Log.i("RankOfflineRealImportTest", "movieImportMs=${SystemClock.elapsedRealtime() - started} batch=400 result=$result")
            assertTrue(result is CatalogImportResult.Imported)
            assertEquals((result as CatalogImportResult.Imported).itemCount,
                database.mediaCatalogDao().itemCount(manifest.packId.value))
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            root.deleteRecursively()
        }
    } }

    @Test fun readyCatalogRemainsQueryableDuringAnotherRealImport() { runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val input = requireNotNull(context.getExternalFilesDir(null))
        val first = File(input, "rankoffline-test-pack-a.pack")
        val second = File(input, "rankoffline-test-pack-b.pack")
        assumeTrue("Real pack benchmark inputs are absent", first.isFile && second.isFile)
        val databaseName = "rankoffline-real-import-test.db"
        val root = File(context.cacheDir, "real-pack-import-test")
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
        check(root.mkdirs())
        val database = Room.databaseBuilder(context, RankOfflineDatabase::class.java, databaseName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        try {
            val installer = PackInstaller(root)
            val importer = CatalogImporter(database)
            val (readyManifest, readyDirectory) = install(installer, first)
            val firstStart = SystemClock.elapsedRealtime()
            val firstResult = importer.importIfNeeded(readyManifest, readyDirectory)
            Log.i("RankOfflineRealImportTest", "firstImportMs=${SystemClock.elapsedRealtime() - firstStart} result=$firstResult")
            installer.commitInstallation(readyManifest.packId)
            val repository = MediaRepository(database, RatingRepository(database))
            repeat(3) { iteration ->
                listOf(false, true).forEach { merge ->
                    val started = SystemClock.elapsedRealtime()
                    val entries = repository.search(readyManifest, SearchUiState(), merge)
                    Log.i("RankOfflineRealImportTest", "merge=$merge run=$iteration " +
                        "searchMs=${SystemClock.elapsedRealtime() - started} entries=${entries.size}")
                }
            }
            val (importingManifest, importingDirectory) = install(installer, second)
            val secondStart = SystemClock.elapsedRealtime()
            val importing = async(Dispatchers.IO) {
                importer.importIfNeeded(importingManifest, importingDirectory)
            }
            val read = async(Dispatchers.IO) {
                repository.search(readyManifest, SearchUiState(), mergeEnabled = false)
            }
            val queryStart = SystemClock.elapsedRealtime()
            val entries = withTimeout(10_000) { read.await() }
            val queryMs = SystemClock.elapsedRealtime() - queryStart
            assertTrue("READY pack must remain queryable", entries.isNotEmpty())
            Log.i("RankOfflineRealImportTest", "queryDuringImportMs=$queryMs importingActive=${importing.isActive}")
            val allPage = withTimeout(10_000) {
                repository.searchAllPage(listOf(readyManifest),
                    mapOf(readyManifest.packId to SearchUiState()),
                    mapOf(readyManifest.packId to false), 20)
            }
            assertTrue("All must show READY packs during another import", allPage.entries.isNotEmpty())
            val secondResult = withTimeout(300_000) { importing.await() }
            Log.i("RankOfflineRealImportTest", "secondImportMs=${SystemClock.elapsedRealtime() - secondStart} result=$secondResult")
            assertTrue(secondResult is CatalogImportResult.Imported)
            assertEquals((firstResult as CatalogImportResult.Imported).itemCount,
                database.mediaCatalogDao().itemCount(readyManifest.packId.value))
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            root.deleteRecursively()
        }
    } }

    private fun install(installer: PackInstaller, archive: File): Pair<PackManifest, File> {
        lateinit var manifest: PackManifest
        val directory = PackArchiveReader.withVerifiedArchive(archive, null) { request, open ->
            manifest = PackManifestParser.parse(request.manifestJson)
            installer.install(request, open)
        }
        return manifest to directory
    }
}
