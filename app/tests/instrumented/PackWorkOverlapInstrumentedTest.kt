package org.rankoffline.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real WorkManager/HTTPS test on an isolated emulator. Never uses the user's database. */
@RunWith(AndroidJUnit4::class)
class PackWorkOverlapInstrumentedTest {
    @Test fun remoteArchiveDownloadsWhileAnotherRealCatalogImports() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("This test may run only on an isolated emulator",
            Build.HARDWARE.startsWith("ranchu") || Build.HARDWARE.startsWith("goldfish"))
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        assumeTrue("The emulator must have validated Internet for the real HTTPS transfer",
            network != null && connectivity.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        val existingPacks = File(context.filesDir, "packs")
        assumeTrue("The emulator contains app pack data", existingPacks.listFiles().isNullOrEmpty())
        val inputs = requireNotNull(context.getExternalFilesDir(null))
        val anime = File(inputs, "rankoffline-test-pack-a.pack")
        val index = File(inputs, "rankoffline-test-pack-index.json")
        assumeTrue("Verified archive and release index are required", anime.isFile && index.isFile)
        val hosts = BuildConfig.PACK_ALLOWED_HOSTS.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        val descriptors = PackIndexParser.parse(index.readText(), TrustedHttpsHosts(hosts))
            .filter { it.packId.value in 1L..2L }
        assertEquals(2, descriptors.size)
        assertTrue(PackArchiveReader.verifyArchiveDigest(anime,
            descriptors.first { it.packId.value == 1L }))

        val root = File(context.cacheDir, "work-overlap-test")
        val databaseName = "work-overlap-test.db"
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
        check(root.mkdirs())
        val database = Room.databaseBuilder(context, RankOfflineDatabase::class.java, databaseName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        val importStarted = CompletableDeferred<Unit>()
        val aImportStart = AtomicLong()
        val aImportEnd = AtomicLong()
        val bTransferStart = AtomicLong()
        val bTransferEnd = AtomicLong()
        val idA = PackId(1)
        val idB = PackId(2)
        val registry = PackSourceRegistry().apply { replace(descriptors) }
        val remote = HttpsPackArchiveDownloader(hosts)
        val downloader = PackArchiveDownloader { descriptor, destination, progress ->
            if (descriptor.packId == idA) {
                withContext(Dispatchers.IO) { anime.copyTo(destination, overwrite = true) }
                progress(destination.length())
                PackOperationResult.INSTALLED
            } else {
                withTimeout(120_000) { importStarted.await() }
                bTransferStart.set(SystemClock.elapsedRealtime())
                remote.download(descriptor, destination, progress).also {
                    bTransferEnd.set(SystemClock.elapsedRealtime())
                    Log.i(TAG, "pack=2 phase=download durationMs=${bTransferEnd.get() - bTransferStart.get()} result=$it")
                }
            }
        }
        val importer = CatalogImporter(database)
        val synchronizer = object : PackCatalogSynchronizer {
            override suspend fun sync(manifest: PackManifest, installedDirectory: File) {
                if (manifest.packId == idA) {
                    aImportStart.set(SystemClock.elapsedRealtime())
                    importStarted.complete(Unit)
                }
                try { importer.importIfNeeded(manifest, installedDirectory) }
                finally { if (manifest.packId == idA) {
                    aImportEnd.set(SystemClock.elapsedRealtime())
                    Log.i(TAG, "pack=1 phase=import durationMs=${aImportEnd.get() - aImportStart.get()}")
                } }
            }
        }
        val manager = PackManager(File(root, "packs"), File(root, "cache"), registry,
            downloader, synchronizer, RoomPackCatalogCleaner(database), BuildConfig.VERSION_NAME)
        PackWorkerRuntimeProvider.configure { object : PackWorkerRuntime {
            override val packManager = manager
            override suspend fun prepare(packId: PackId, action: PackWorkAction) = Unit
            override suspend fun resumeInitialCoversIfNeeded(packId: PackId) = true
        } }
        try {
            PackWorkScheduler.enqueue(context, idA, PackWorkAction.INSTALL)
            PackWorkScheduler.enqueue(context, idB, PackWorkAction.INSTALL)
            withTimeout(300_000) {
                while (true) {
                    val a = WorkManager.getInstance(context).getWorkInfosForUniqueWork(
                        PackWorkScheduler.uniqueName(idA, PackWorkAction.INSTALL)).get()
                    val b = WorkManager.getInstance(context).getWorkInfosForUniqueWork(
                        PackWorkScheduler.uniqueName(idB, PackWorkAction.INSTALL)).get()
                    if (a.isNotEmpty() && b.isNotEmpty() &&
                        a.all { it.state.isFinished } && b.all { it.state.isFinished }) {
                        assertTrue("Pack A install or initial covers failed: $a",
                            a.all { it.state == androidx.work.WorkInfo.State.SUCCEEDED })
                        assertTrue("Pack B install or initial covers failed: $b",
                            b.all { it.state == androidx.work.WorkInfo.State.SUCCEEDED })
                        break
                    }
                    delay(250)
                }
            }
            Log.i(TAG, "aImport=${aImportStart.get()}..${aImportEnd.get()} bDownload=${bTransferStart.get()}..${bTransferEnd.get()}")
            assertTrue("B must download during A import", bTransferStart.get() >= aImportStart.get() &&
                bTransferEnd.get() <= aImportEnd.get())
        } finally {
            PackWorkScheduler.cancel(context, idA)
            PackWorkScheduler.cancel(context, idB)
            database.close()
            context.deleteDatabase(databaseName)
            root.deleteRecursively()
        }
    }

    private companion object { const val TAG = "RankOfflineWorkOverlap" }
}
