package org.rankoffline.app

import android.content.Context
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
            val (importingManifest, importingDirectory) = install(installer, second)
            val secondStart = SystemClock.elapsedRealtime()
            val importing = async(Dispatchers.IO) {
                importer.importIfNeeded(importingManifest, importingDirectory)
            }
            val repository = MediaRepository(database, RatingRepository(database))
            val read = async(Dispatchers.IO) {
                repository.search(readyManifest, SearchUiState(), mergeEnabled = false)
            }
            val queryStart = SystemClock.elapsedRealtime()
            val entries = withTimeout(10_000) { read.await() }
            val queryMs = SystemClock.elapsedRealtime() - queryStart
            assertTrue("READY pack must remain queryable", entries.isNotEmpty())
            Log.i("RankOfflineRealImportTest", "queryDuringImportMs=$queryMs importingActive=${importing.isActive}")
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
