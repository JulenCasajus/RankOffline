package org.rankoffline.app

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real WorkManager workers with controlled transport and an isolated pack/Room store. */
@RunWith(AndroidJUnit4::class)
class InitialCoverResumeInstrumentedTest {
    @Test fun initialCoversResumeBeforeAnotherPackFinishesImporting() = runScenario(false)

    @Test fun initialCoversStallAfterInFlightHttpWhileImportHoldsRoomWriter() = runScenario(true)

    @Test fun failedArchiveReleasesInitialCovers() = runScenario(false, failArchive = true)

    @Test fun cancelledArchiveReleasesInitialCovers() = runScenario(false, cancelArchive = true)

    @Test fun cancellingInitialCoversStopsTheRealWorker() = runScenario(false, cancelInitial = true)

    private fun runScenario(
        holdRoomWriter: Boolean, failArchive: Boolean = false, cancelArchive: Boolean = false,
        cancelInitial: Boolean = false
    ) = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Run only on an isolated emulator",
            Build.HARDWARE.startsWith("ranchu") || Build.HARDWARE.startsWith("goldfish"))
        val root = File(base.cacheDir, "initial-cover-resume-test-$holdRoomWriter")
        root.deleteRecursively()
        check(root.mkdirs())
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getCacheDir(): File = File(root, "cache").also { it.mkdirs() }
        }
        val databaseName = "initial-cover-resume-$holdRoomWriter.db"
        base.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(base, RankOfflineDatabase::class.java, databaseName)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        // Unique WorkManager names for every run: KEEP must not reuse terminal test work.
        val idA = PackId(1_000_000L + SystemClock.elapsedRealtime() * 2L)
        val idB = PackId(idA.value + 1L)
        val scheduler = PackOperationScheduler()
        val coverGate = CompletableDeferred<Unit>()
        val archiveAcquired = CompletableDeferred<Unit>()
        val archiveRelease = CompletableDeferred<Unit>()
        val importStarted = CompletableDeferred<Unit>()
        val importRelease = CompletableDeferred<Unit>()
        val archiveReleasedAt = AtomicLong()
        val importFinishedAt = AtomicLong()
        val coversFinishedAt = AtomicLong()
        val coversResult = AtomicReference<CoverDownloadProgress>()
        val attempts = AtomicInteger()
        val httpCompleted = AtomicInteger()
        val imageBytes = ByteArrayOutputStream().also { output ->
            val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            try { check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            finally { image.recycle() }
        }.toByteArray()

        val archive = fixtureArchive(idB.value)
        val registry = PackSourceRegistry().apply { replace(listOf(PackDownloadDescriptor(
            TestFixtures.manifest(idB.value), URI("https://example.com/test.pack"),
            archive.size.toLong(), TestFixtures.sha256(archive), "fixture"
        ))) }
        val manager = PackManager(File(root, "packs"), File(root, "archives"), registry,
            PackArchiveDownloader { _, destination, progress ->
                archiveAcquired.complete(Unit)
                archiveRelease.await()
                if (failArchive) return@PackArchiveDownloader PackOperationResult.INVALID_PACK
                withContext(Dispatchers.IO) { destination.writeBytes(archive) }
                progress(archive.size.toLong())
                PackOperationResult.INSTALLED
            },
            object : PackCatalogSynchronizer {
                override suspend fun sync(manifest: PackManifest, installedDirectory: File) {
                    if (manifest.packId == idB) {
                        if (holdRoomWriter) database.withTransaction {
                            database.mediaCatalogDao().insertNewItems(listOf(
                                MediaItemEntity(idB.value, "importing", "Importing")
                            ))
                            importStarted.complete(Unit)
                            importRelease.await()
                        } else {
                            importStarted.complete(Unit)
                            importRelease.await()
                        }
                        importFinishedAt.set(SystemClock.elapsedRealtime())
                    }
                }
            }, RoomPackCatalogCleaner(database), "0.2.2", scheduler = scheduler)
        val repository = CoverRepository(isolated, database,
            manifestProvider = { id -> TestFixtures.manifest(id.value) },
            downloader = CoverDownloader { _, destination ->
                val attempt = attempts.incrementAndGet()
                if (attempt > 3) coverGate.await()
                scheduler.withCoverTransfer {
                    if (holdRoomWriter && attempt > 3) importStarted.await()
                    destination.writeBytes(imageBytes)
                    httpCompleted.incrementAndGet()
                    CoverTransferResult.DOWNLOADED
                }
            }, beforeCoverMutation = { _, _ -> })
        val covers = CoverDownloadsManager(repository)
        val keys = (0 until 20).map { CatalogEntryKey(idA, "cover-$it", CatalogEntryKind.ITEM) }

        try {
            val directory = File(root, "packs/${idA.directoryName()}")
            check(directory.mkdirs())
            File(directory, PackInstaller.PACK_MANIFEST).writeText(TestFixtures.manifestJson(idA.value))
            File(directory, PackInstaller.COMPLETE_MARKER).writeText("fixture")
            database.mediaCatalogDao().insertNewItems(keys.map { key ->
                MediaItemEntity(idA.value, key.id, key.id, provider = "fixture", remoteCoverRef = key.id)
            })
            manager.runtime[idA] = PackRuntimeStatus.READY
            manager.refresh()
            PackWorkerRuntimeProvider.configure { object : PackWorkerRuntime {
                override val packManager = manager
                override suspend fun prepare(packId: PackId, action: PackWorkAction) = Unit
                override suspend fun resumeInitialCoversIfNeeded(packId: PackId): Boolean {
                    if (packId != idA) return true
                    val result = manager.acquireInitialCovers(packId, 20) { onProgress ->
                        covers.downloadSelected(packId, keys, onProgress)
                    }
                    coversResult.set(result)
                    coversFinishedAt.set(SystemClock.elapsedRealtime())
                    return result.status == CoverDownloadStatus.COMPLETED
                }
            } }

            covers.setProgressVisible(idA, true)
            PackWorkScheduler.enqueue(base, idA, PackWorkAction.INITIAL_COVERS)
            withTimeout(20_000) { covers.progressByPack.first { it[idA]?.downloaded == 3 } }
            Log.i(TAG, "pack=${idA.value} phase=three-covers atMs=${SystemClock.elapsedRealtime()}")
            if (cancelInitial) {
                PackWorkScheduler.cancelInitialCovers(base, idA)
                withTimeout(20_000) {
                    while (WorkManager.getInstance(base).getWorkInfosForUniqueWork(
                            PackWorkScheduler.uniqueName(idA, PackWorkAction.INITIAL_COVERS))
                            .get().none { it.state == WorkInfo.State.CANCELLED }) delay(20)
                }
                withTimeout(20_000) { covers.progressByPack.first {
                    it[idA]?.status == CoverDownloadStatus.CANCELLED
                } }
                assertEquals(3, covers.progressByPack.value[idA]?.downloaded)
                assertEquals(0, covers.progressByPack.value[idA]?.errors)
                assertEquals(CoverDownloadStatus.CANCELLED, covers.progressByPack.value[idA]?.status)
                return@runBlocking
            }
            PackWorkScheduler.enqueue(base, idB, PackWorkAction.INSTALL)
            withTimeout(20_000) { archiveAcquired.await() }
            coverGate.complete(Unit)
            delay(250)
            assertEquals("A must pause new transfers while B owns the archive slot",
                3, covers.progressByPack.value[idA]?.downloaded)
            if (cancelArchive) PackWorkScheduler.cancel(base, idB)
            else archiveRelease.complete(Unit)
            archiveReleasedAt.set(SystemClock.elapsedRealtime())
            if (!failArchive && !cancelArchive) withTimeout(20_000) { importStarted.await() }
            if (holdRoomWriter) {
                withTimeout(20_000) { while (httpCompleted.get() < 10) delay(10) }
                // HTTP can run briefly, but all workers eventually wait for the same
                // SQLite writer. This is not sustained cover-download progress.
                delay(400)
                val stalledAt = httpCompleted.get()
                val committedAt = covers.progressByPack.value[idA]?.downloaded
                delay(400)
                assertEquals(stalledAt, httpCompleted.get())
                assertTrue("A bounded batch must stall before all images are committed",
                    stalledAt < keys.size)
                assertEquals("Completed covers stop advancing behind Room's writer",
                    committedAt, covers.progressByPack.value[idA]?.downloaded)
                assertFalse("B import must still hold Room while A downloads", importRelease.isCompleted)
                assertEquals("A awaits the Room writer before promoting all images", 0L, coversFinishedAt.get())
                Log.i(TAG, "archiveReleased=${archiveReleasedAt.get()} importRoomHeld=true " +
                    "coverHttpCompleted=${httpCompleted.get()} atMs=${SystemClock.elapsedRealtime()}")
                importRelease.complete(Unit)
            } else if (!failArchive && !cancelArchive) {
                withTimeout(30_000) { while (coversFinishedAt.get() == 0L) delay(10) }
                assertFalse("B import must still be running when A finishes covers", importRelease.isCompleted)
                importRelease.complete(Unit)
            }
            withTimeout(30_000) { while (coversFinishedAt.get() == 0L) delay(10) }
            assertEquals(20, coversResult.get()?.downloaded)
            assertEquals(0, coversResult.get()?.errors)
            val paths = keys.map { key -> repository.observeCover(key).first().localPath }
            assertEquals(20, paths.count { it != null })
            assertEquals("Each cover has one physical identity", 20, paths.distinct().size)
            assertTrue(paths.all { path -> path != null && File(path).isFile })
            assertEquals("No image request is repeated after priority suspension", 20, attempts.get())
            assertTrue(coversFinishedAt.get() >= archiveReleasedAt.get())
            Log.i(TAG, "archiveReleased=${archiveReleasedAt.get()} coversFinished=${coversFinishedAt.get()} importStarted=${SystemClock.elapsedRealtime()}" )
            withTimeout(30_000) {
                while (true) {
                    val a = WorkManager.getInstance(base).getWorkInfosForUniqueWork(
                        PackWorkScheduler.uniqueName(idA, PackWorkAction.INITIAL_COVERS)).get()
                    val b = WorkManager.getInstance(base).getWorkInfosForUniqueWork(
                        PackWorkScheduler.uniqueName(idB, PackWorkAction.INSTALL)).get()
                    if (a.isNotEmpty() && b.isNotEmpty() && a.all { it.state.isFinished } &&
                        b.all { it.state.isFinished }) {
                        assertTrue(a.all { it.state == WorkInfo.State.SUCCEEDED })
                        when {
                            cancelArchive -> assertTrue(b.any { it.state == WorkInfo.State.CANCELLED })
                            failArchive -> assertTrue(b.any { it.state == WorkInfo.State.FAILED })
                            else -> assertTrue(b.all { it.state == WorkInfo.State.SUCCEEDED })
                        }
                        break
                    }
                    delay(100)
                }
            }
            if (!holdRoomWriter && !failArchive && !cancelArchive)
                assertTrue(importFinishedAt.get() > coversFinishedAt.get())
        } finally {
            coverGate.complete(Unit)
            archiveRelease.complete(Unit)
            importRelease.complete(Unit)
            PackWorkScheduler.cancel(base, idA)
            PackWorkScheduler.cancel(base, idB)
            database.close()
            base.deleteDatabase(databaseName)
            root.deleteRecursively()
        }
    }

    private fun fixtureArchive(packId: Long): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in listOf(
                "manifest.json" to TestFixtures.manifestJson(packId).toByteArray(),
                "catalog/items.jsonl" to TestFixtures.catalogBytes
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private companion object { const val TAG = "RankOfflineCoverResumeTest" }
}
