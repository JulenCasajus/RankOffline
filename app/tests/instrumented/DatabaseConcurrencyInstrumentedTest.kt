package org.rankoffline.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseConcurrencyInstrumentedTest {
    private val context by lazy { ApplicationProvider.getApplicationContext<Context>() }
    private lateinit var database: RankOfflineDatabase
    private val name = "rankoffline-reader-during-import-test.db"

    @Before fun createDatabase() {
        context.deleteDatabase(name)
        database = Room.databaseBuilder(context, RankOfflineDatabase::class.java, name)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }

    @After fun closeDatabase() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test fun alreadyCommittedCatalogCanBeReadWhileAnotherPackWrites() { runBlocking {
        database.mediaCatalogDao().insertNewItems(listOf(MediaItemEntity(11, "ready", "Ready")))
        database.mediaGroupDao().insertNewGroups(listOf(
            MediaGroupEntity(11, "ready-group", "relations", "Ready group")
        ))
        database.mediaGroupDao().insertMemberships(listOf(
            MediaGroupMemberEntity(11, "ready-group", "ready", 0)
        ))
        val writerStarted = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val writer = async(Dispatchers.IO) {
            database.withTransaction {
                database.mediaCatalogDao().insertNewItems(listOf(MediaItemEntity(12, "new", "New")))
                writerStarted.complete(Unit)
                releaseWriter.await()
            }
        }

        try {
            withTimeout(5_000) { writerStarted.await() }
            val repository = MediaRepository(database, RatingRepository(database))
            val manifest = PackManifestParser.parse(TestFixtures.manifestJson(packId = 11))
            val item = withTimeout(5_000) {
                repository.item(
                    CatalogEntryKey(PackId(11), "ready", CatalogEntryKind.ITEM)
                )
            }
            assertEquals("Ready", item?.title)
            val covers = CoverRecordStore(database.mediaDao(), database.mediaGroupDao(),
                DeclarativePackCoverProvider { manifest })
            val itemKey = CatalogEntryKey(PackId(11), "ready", CatalogEntryKind.ITEM)
            val groupKey = CatalogEntryKey(PackId(11), "ready-group", CatalogEntryKind.GROUP)
            assertEquals("UNKNOWN", withTimeout(5_000) {
                covers.observeSnapshot(itemKey).first()?.resolutionState
            })
            assertEquals("UNKNOWN", withTimeout(5_000) {
                covers.observeSnapshot(groupKey).first()?.resolutionState
            })
            assertEquals(listOf("ready"), withTimeout(5_000) {
                covers.observeFallbackItems(groupKey).first().map { it.id }
            })
            assertEquals(listOf("ready"), withTimeout(5_000) {
                database.mediaGroupDao().coverCandidateItems(11, "ready-group").map { it.id }
            })
            val rows = withTimeout(5_000) {
                database.mediaDao().visibleEntries(CatalogQueries.catalog(
                    manifest, SearchUiState(), merge = false, limit = 200))
            }
            assertEquals(listOf("ready"), rows.map { it.id })
            assertEquals(listOf("ready"), withTimeout(5_000) {
                repository.search(manifest, SearchUiState(), mergeEnabled = false).map { it.id }
            })
            assertEquals(listOf("ready-group"), withTimeout(5_000) {
                database.mediaDao().visibleEntries(CatalogQueries.popularCovers(manifest, 200, 0))
                    .map { it.id }
            })
            assertEquals(emptyList<String>(), withTimeout(5_000) {
                repository.ranking(manifest, RankingUiState(), mergeEnabled = false).map { it.id }
            })
            assertEquals(RatingDraft(), withTimeout(5_000) {
                repository.ratings.loadRating(CatalogEntryKey(PackId(11), "ready", CatalogEntryKind.ITEM))
            })
        } finally {
            releaseWriter.complete(Unit)
            withTimeout(5_000) { writer.await() }
        }
    } }

    @Test fun coverRoomWriteDoesNotHoldNetworkSlotDuringAnotherImport() { runBlocking {
        database.mediaCatalogDao().insertNewItems(listOf(MediaItemEntity(11, "cover", "Cover")))
        val key = CatalogEntryKey(PackId(11), "cover", CatalogEntryKind.ITEM)
        val record = CoverRecord(key, null, null, CoverResolutionState.UNKNOWN.name, emptyList())
        val records = CoverRecordStore(database.mediaDao(), database.mediaGroupDao(),
            DeclarativePackCoverProvider { null })
        val scheduler = PackOperationScheduler(maxConcurrentCovers = 1)
        val coverHttpFinished = CompletableDeferred<Unit>()
        val allowCoverDb = CompletableDeferred<Unit>()
        val coverDbStarted = CompletableDeferred<Unit>()
        val importStarted = CompletableDeferred<Unit>()
        val releaseImport = CompletableDeferred<Unit>()
        val archiveStarted = CompletableDeferred<Long>()
        val cover = async(Dispatchers.IO) {
            scheduler.withCoverTransfer {
                coverHttpFinished.complete(Unit) // Deterministic end of the image HTTP phase.
            }
            allowCoverDb.await()
            coverDbStarted.complete(Unit)
            records.updateIfCurrent(record, "/tmp/test-cover", CoverResolutionState.AVAILABLE)
        }
        try {
            withTimeout(5_000) { coverHttpFinished.await() }
            val importer = async(Dispatchers.IO) {
                database.withTransaction {
                    database.mediaCatalogDao().insertNewItems(listOf(MediaItemEntity(12, "new", "New")))
                    importStarted.complete(Unit)
                    releaseImport.await()
                }
            }
            try {
                withTimeout(5_000) { importStarted.await() }
                allowCoverDb.complete(Unit)
                withTimeout(5_000) { coverDbStarted.await() }
                val archive = async(Dispatchers.IO) {
                    scheduler.withArchiveTransfer { archiveStarted.complete(SystemClock.elapsedRealtime()) }
                }
                try {
                    withTimeout(5_000) { archiveStarted.await() }
                    assertFalse("Import must still hold its Room transaction", importer.isCompleted)
                    assertFalse("Cover Room write must still be waiting", cover.isCompleted)
                    Log.i("RankOfflineContention", "coverDbWaiting=true archiveStarted=true " +
                        "atMs=${SystemClock.elapsedRealtime()}")
                } finally {
                    releaseImport.complete(Unit)
                    withTimeout(5_000) { importer.await(); cover.await(); archive.await() }
                }
            } finally { releaseImport.complete(Unit) }
        } finally { allowCoverDb.complete(Unit) }
    } }

    @Test fun coverHttpKeepsMovingWhileAnotherPackHoldsRoomWriter() { runBlocking {
        val id = PackId(11)
        val keys = (0 until 20).map { CatalogEntryKey(id, "cover-$it", CatalogEntryKind.ITEM) }
        database.mediaCatalogDao().insertNewItems(keys.map { key ->
            MediaItemEntity(id.value, key.id, key.id, provider = "fixture", remoteCoverRef = key.id)
        })
        val httpCompleted = AtomicInteger()
        val repository = CoverRepository(context, database,
            manifestProvider = { TestFixtures.manifest(it.value) },
            downloader = CoverDownloader { _, _ ->
                httpCompleted.incrementAndGet()
                CoverTransferResult.NOT_FOUND
            }, beforeCoverMutation = { _, _ -> })
        val manager = CoverDownloadsManager(repository)
        val writerStarted = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val writer = async(Dispatchers.IO) {
            database.withTransaction {
                database.mediaCatalogDao().insertNewItems(listOf(MediaItemEntity(12, "new", "New")))
                writerStarted.complete(Unit)
                releaseWriter.await()
            }
        }
        try {
            withTimeout(5_000) { writerStarted.await() }
            val batch = async(Dispatchers.IO) { manager.downloadSelected(id, keys) }
            try {
                withTimeout(5_000) { while (httpCompleted.get() < keys.size) delay(10) }
                assertEquals("An unrelated Room writer must not stop image HTTP", 20, httpCompleted.get())
                withTimeout(5_000) {
                    while (manager.progressByPack.value[id]?.processed != 20) delay(10)
                }
                assertFalse("Only the final Room flush should wait for the import", batch.isCompleted)
                assertFalse("Catalog import must still be active", writer.isCompleted)
            } finally {
                releaseWriter.complete(Unit)
                assertEquals(20, withTimeout(10_000) { batch.await() }.unavailable)
                assertEquals(20, database.mediaDao().notFoundCoverCount(id.value))
            }
        } finally {
            releaseWriter.complete(Unit)
            withTimeout(10_000) { writer.await() }
        }
    } }
}
