package org.rankoffline.app

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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
}
