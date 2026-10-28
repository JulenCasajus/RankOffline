package org.rankoffline.app

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadyPackReadConcurrencyTest {
    @Test
    fun readyPackReadsContinueDuringAnotherPackTransactionAndRollbackPreservesData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "ready-pack-read-${UUID.randomUUID()}.db"
        val queryExecutor = Executors.newFixedThreadPool(2)
        val transactionExecutor = Executors.newSingleThreadExecutor()
        val database = Room.databaseBuilder(context, AnimeDatabase::class.java, databaseName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryExecutor(queryExecutor)
            .setTransactionExecutor(transactionExecutor)
            .build()
        try {
            val repository = GenericMediaRepository(database)
            val anime = MediaItemEntity(
                id = "mal:16498",
                mediaType = MediaType.ANIME.persistedValue,
                provider = "mal",
                providerId = "16498",
                title = "Attack on Titan",
                popularityRank = 1
            )
            repository.upsertItem(anime)
            repository.saveSimpleRating(anime.id, RatingDraft(notes = "preserve"), 9.0, 9.0)
            // READY publication preloads this small relationship projection before the next
            // serialized import can start.
            repository.refreshSharedCoverIdentities(MediaType.ANIME, "RELATIONS")
            val sqlite = database.openHelper.writableDatabase
            val journalMode = sqlite.query("PRAGMA journal_mode").use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            assertEquals("wal", journalMode.lowercase())
            assertTrue(sqlite.isWriteAheadLoggingEnabled)

            val transactionStarted = CompletableDeferred<Unit>()
            val releaseTransaction = CompletableDeferred<Unit>()
            val importResult = async(Dispatchers.IO) {
                runCatching {
                    database.withTransaction {
                        database.mediaDao().upsertItem(
                            MediaItemEntity(
                                id = "book:openlibrary:OL1W",
                                mediaType = MediaType.BOOK.persistedValue,
                                provider = "openlibrary",
                                providerId = "OL1W",
                                title = "Uncommitted Book"
                            )
                        )
                        transactionStarted.complete(Unit)
                        releaseTransaction.await()
                        error("Simulated import failure")
                    }
                }
            }
            transactionStarted.await()

            val elapsed: Long
            try {
                elapsed = measureTimeMillis {
                    withTimeout(3_000L) {
                        val rawCount = database.openHelper.readableDatabase
                            .query("SELECT COUNT(*) FROM media_item WHERE media_type='anime'")
                            .use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
                        Log.d("RankOfflineCatalog", "operation=test-raw-read count=$rawCount")
                        val search = repository.search(
                            MediaType.ANIME,
                            "Attack",
                            CatalogSortOrder.POPULARITY,
                            GenericMediaFilters(),
                            mergeEnabled = false
                        )
                        val ranking = repository.ranking(
                            MediaType.ANIME,
                            GenericMediaFilters(),
                            RankingSortOrder.FINAL_SCORE,
                            categoryId = null,
                            descending = true,
                            mergeEnabled = false
                        )
                        assertEquals(listOf(anime.id), search.map(MediaListEntry::id))
                        assertEquals(listOf(anime.id), ranking.map(MediaListEntry::id))
                        assertEquals("preserve", repository.loadRating(MediaType.ANIME, anime.id).notes)
                    }
                }
            } finally {
                releaseTransaction.complete(Unit)
            }
            assertTrue("READY pack reads took ${elapsed}ms", elapsed < 2_500L)

            assertTrue(importResult.await().isFailure)
            assertNull(database.mediaDao().item("book:openlibrary:OL1W"))
            assertEquals("preserve", repository.loadRating(MediaType.ANIME, anime.id).notes)
        } finally {
            database.close()
            queryExecutor.shutdownNow()
            transactionExecutor.shutdownNow()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun packPurgeQueuedDuringAnotherImportCompletesAfterTheActiveWriter() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "import-purge-${UUID.randomUUID()}.db"
        val queryExecutor = Executors.newFixedThreadPool(2)
        val transactionExecutor = Executors.newSingleThreadExecutor()
        val database = Room.databaseBuilder(context, AnimeDatabase::class.java, databaseName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryExecutor(queryExecutor)
            .setTransactionExecutor(transactionExecutor)
            .build()
        try {
            val repository = GenericMediaRepository(database)
            val manga = MediaItemEntity(
                id = "mangadex:manga-1",
                mediaType = MediaType.MANGA.persistedValue,
                provider = "mangadex",
                providerId = "manga-1",
                title = "Manga"
            )
            repository.upsertItem(manga)
            repository.saveSimpleRating(manga.id, RatingDraft(notes = "delete"), 8.0, 8.0)

            val importStarted = CompletableDeferred<Unit>()
            val releaseImport = CompletableDeferred<Unit>()
            val import = async(Dispatchers.IO) {
                database.withTransaction {
                    database.mediaDao().upsertItem(
                        MediaItemEntity(
                            id = "book:openlibrary:OL1W",
                            mediaType = MediaType.BOOK.persistedValue,
                            provider = "openlibrary",
                            providerId = "OL1W",
                            title = "Book"
                        )
                    )
                    importStarted.complete(Unit)
                    releaseImport.await()
                }
            }
            importStarted.await()
            val purge = async(Dispatchers.IO) { repository.purgePackData(MediaType.MANGA) }

            delay(100)
            assertFalse("A second SQLite writer must wait for the active import", purge.isCompleted)
            releaseImport.complete(Unit)
            withTimeout(3_000L) {
                import.await()
                purge.await()
            }

            assertNull(database.mediaDao().item(manga.id))
            assertNull(database.mediaDao().rating(MediaType.MANGA.toPackId().value, manga.id))
            assertEquals(
                "Book",
                database.mediaDao().item("book:openlibrary:OL1W")?.title
            )
        } finally {
            database.close()
            queryExecutor.shutdownNow()
            transactionExecutor.shutdownNow()
            context.deleteDatabase(databaseName)
        }
    }
}
