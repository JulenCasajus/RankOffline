package org.rankoffline.app

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverProgressVisibilityInstrumentedTest {
    @Test fun decodedGroupCoverCanBeReusedBeforeRoomEmitsItsFallbackPath() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.cacheDir, "warm-cover-test.png")
        val bitmap = Bitmap.createBitmap(1000, 1500, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        try {
            val cache = CoverBitmapCache()
            val identity = CoverStorageIdentity(PackId(78), "group", CatalogEntryKind.GROUP).cacheIdentity()
            val other = CoverStorageIdentity(PackId(79), "group", CatalogEntryKind.GROUP).cacheIdentity()
            val thumbnail = cache.loadBitmap(identity, file.absolutePath)
            assertNotNull(thumbnail)
            assertTrue(thumbnail!!.width <= 384 && thumbnail.height <= 384)
            assertNotNull(cache.loadBitmap(other, file.absolutePath))
            assertEquals(file.absolutePath, cache.cachedCover(identity)?.first)
            cache.invalidatePack(PackId(78))
            assertNull(cache.cachedCover(identity))
            assertNotNull(cache.cachedCover(other))
        } finally { file.delete() }
    }

    @Test fun fiftyRecentlyDecodedCatalogCoversRemainResident() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.cacheDir, "scroll-cover-test.png")
        val bitmap = Bitmap.createBitmap(1000, 1500, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        try {
            val cache = CoverBitmapCache()
            val ids = (0 until 50).map { index ->
                CoverStorageIdentity(PackId(78), "item-$index", CatalogEntryKind.ITEM).cacheIdentity()
            }
            ids.forEach { assertNotNull(cache.loadBitmap(it, file.absolutePath)) }
            ids.forEach { assertNotNull("A recent cover was decoded again after scrolling back", cache.cachedBitmap(it, file.absolutePath)) }
        } finally { file.delete() }
    }

    @Test fun completedBatchStaysVisibleUntilLeavingCovers() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java).build()
        try {
            val repository = CoverRepository(context, database, { null }) { _, _ -> }
            val manager = CoverDownloadsManager(repository)
            val id = PackId(77)
            manager.setProgressVisible(id, true)
            manager.downloadSelected(id, emptyList())
            assertEquals(CoverDownloadStatus.COMPLETED, manager.progressByPack.value[id]?.status)
            manager.setProgressVisible(id, false)
            assertNull(manager.progressByPack.value[id])
            manager.downloadSelected(id, emptyList())
            assertNull(manager.progressByPack.value[id])
        } finally { database.close() }
    }

    @Test fun concurrentPackBatchResultsKeepSeparateProgress() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java).build()
        try {
            val repository = CoverRepository(context, database, { null }) { _, _ -> }
            val manager = CoverDownloadsManager(repository)
            val first = PackId(76)
            val second = PackId(77)
            manager.setProgressVisible(first, true)
            manager.setProgressVisible(second, true)
            manager.downloadSelected(first, emptyList())
            manager.downloadSelected(second, emptyList())
            assertEquals(CoverDownloadStatus.COMPLETED, manager.progressByPack.value[first]?.status)
            assertEquals(CoverDownloadStatus.COMPLETED, manager.progressByPack.value[second]?.status)
            manager.setProgressVisible(first, false)
            assertNull(manager.progressByPack.value[first])
            assertEquals(CoverDownloadStatus.COMPLETED, manager.progressByPack.value[second]?.status)
        } finally {
            database.close()
        }
    }

    @Test fun secondPackBatchStartsWhileFirstPackBatchIsStillActive() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java).build()
        try {
            database.mediaCatalogDao().insertNewItems(listOf(
                MediaItemEntity(76, "first", "First", provider = "fixture", remoteCoverRef = "first"),
                MediaItemEntity(77, "second", "Second", provider = "fixture", remoteCoverRef = "second")
            ))
            val firstRequested = CompletableDeferred<Unit>()
            val secondRequested = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val downloader = CoverDownloader { uri, _ ->
                if (uri.path.contains("first")) {
                    firstRequested.complete(Unit)
                    releaseFirst.await()
                } else secondRequested.complete(Unit)
                CoverTransferResult.NOT_FOUND
            }
            val repository = CoverRepository(context, database,
                manifestProvider = { id -> TestFixtures.manifest(id.value) },
                downloader = downloader, beforeCoverMutation = { _, _ -> })
            val manager = CoverDownloadsManager(repository)
            val first = async(Dispatchers.IO) { manager.downloadSelected(PackId(76), listOf(
                CatalogEntryKey(PackId(76), "first", CatalogEntryKind.ITEM))) }
            try {
                withTimeout(2_000) { firstRequested.await() }
                val second = async(Dispatchers.IO) { manager.downloadSelected(PackId(77), listOf(
                    CatalogEntryKey(PackId(77), "second", CatalogEntryKind.ITEM))) }
                withTimeout(2_000) { secondRequested.await() }
                assertTrue("The first pack must still be active", first.isActive)
                assertEquals(1, withTimeout(5_000) { second.await() }.unavailable)
            } finally {
                releaseFirst.complete(Unit)
                withTimeout(5_000) { first.await() }
            }
        } finally { database.close() }
    }

    @Test fun cancellingManualBatchLeavesNoFalseErrorsOrMissingCovers() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java).build()
        try {
            val id = PackId(75)
            database.mediaCatalogDao().insertNewItems(listOf(
                MediaItemEntity(id.value, "waiting", "Waiting", provider = "fixture",
                    remoteCoverRef = "waiting")))
            val requested = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository = CoverRepository(context, database,
                manifestProvider = { TestFixtures.manifest(it.value) },
                downloader = CoverDownloader { _, _ ->
                    requested.complete(Unit)
                    release.await()
                    CoverTransferResult.NOT_FOUND
                }, beforeCoverMutation = { _, _ -> })
            val manager = CoverDownloadsManager(repository)
            manager.setProgressVisible(id, true)
            val batch = async(Dispatchers.IO) { manager.downloadSelected(id, listOf(
                CatalogEntryKey(id, "waiting", CatalogEntryKind.ITEM))) }
            withTimeout(5_000) { requested.await() }
            batch.cancel()
            try { batch.await() } catch (_: CancellationException) { }
            val progress = manager.progressByPack.value[id]
            assertEquals(CoverDownloadStatus.CANCELLED, progress?.status)
            assertEquals(0, progress?.downloaded)
            assertEquals(0, progress?.unavailable)
            assertEquals(0, progress?.errors)
        } finally { database.close() }
    }
}
