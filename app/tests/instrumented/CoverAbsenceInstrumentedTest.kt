package org.rankoffline.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.net.URI
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverAbsenceInstrumentedTest {
    @Test fun repositoryPassesOnlySelectedSourcesHostsToDownloader() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java).build()
        val base = TestFixtures.manifest(84)
        val source = base.coverSources.single()
        val selected = source.copy(id = "selected", allowedHosts =
            setOf("images.example.com", "selected.example.com"))
        val other = source.copy(id = "other", allowedHosts =
            setOf("images.example.com", "other.example.com"))
        val manifest = base.copy(coverSources = listOf(selected, other))
        val key = CatalogEntryKey(manifest.packId, "x", CatalogEntryKind.ITEM)
        val reference = """{"schemaVersion":1,"sourceId":"selected","identity":"x","urls":["https://images.example.com/x.jpg"]}"""
        var receivedHosts: Set<String>? = null
        try {
            database.mediaCatalogDao().insertNewItems(listOf(MediaItemEntity(key.packId.value,
                key.id, "X", provider = "fixture", remoteCoverRef = reference)))
            val downloader = object : CoverDownloader {
                override suspend fun download(remoteUri: URI, destination: File): CoverTransferResult =
                    error("The source allowlist must be passed")

                override suspend fun download(remoteUri: URI, destination: File,
                    allowedHosts: Set<String>): CoverTransferResult {
                    receivedHosts = allowedHosts
                    return CoverTransferResult.NOT_FOUND
                }
            }
            val repository = CoverRepository(context, database,
                manifestProvider = { manifest.takeIf { m -> m.packId == it } },
                downloader = downloader, beforeCoverMutation = { _, _ -> })
            assertEquals(CoverDownloadResult.NotFound, repository.downloadCover(key))
            assertEquals(selected.allowedHosts, receivedHosts)
            assertFalse(requireNotNull(receivedHosts).contains("other.example.com"))
        } finally { database.close() }
    }

    @Test fun groupTriesEveryMemberAndKeepsRecoverableFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java).build()
        val packId = PackId(83)
        val group = CatalogEntryKey(packId, "group", CatalogEntryKind.GROUP)
        val attempted = mutableListOf<String>()
        try {
            database.mediaCatalogDao().insertNewItems(listOf(
                MediaItemEntity(packId.value, "first", "First", provider = "fixture", remoteCoverRef = "first"),
                MediaItemEntity(packId.value, "second", "Second", provider = "fixture", remoteCoverRef = "second")
            ))
            database.mediaGroupDao().insertNewGroups(listOf(
                MediaGroupEntity(packId.value, group.id, "relations", "Group")))
            database.mediaGroupDao().insertMemberships(listOf(
                MediaGroupMemberEntity(packId.value, group.id, "first", 0),
                MediaGroupMemberEntity(packId.value, group.id, "second", 1)))
            val repository = CoverRepository(context, database,
                manifestProvider = { TestFixtures.manifest(it.value, merge = true) },
                downloader = CoverDownloader { uri, _ ->
                    attempted += uri.toString()
                    if (uri.path.contains("first")) CoverTransferResult.NETWORK_ERROR
                    else CoverTransferResult.NOT_FOUND
                }, beforeCoverMutation = { _, _ -> })
            assertEquals(CoverDownloadResult.NetworkError, repository.downloadCover(group))
            assertEquals(2, attempted.size)
            assertTrue(attempted.any { it.contains("first") })
            assertTrue(attempted.any { it.contains("second") })
            assertEquals(CoverResolutionState.UNKNOWN.name,
                database.mediaGroupDao().group(packId.value, group.id)?.coverResolutionState)
        } finally { database.close() }
    }

    @Test fun definitiveAbsenceSurvivesDatabaseReopenWithoutAnotherRequest() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "cover-absence-${System.nanoTime()}.db"
        val key = CatalogEntryKey(PackId(82), "missing-after-restart", CatalogEntryKind.ITEM)
        val requests = AtomicInteger()
        val downloader = CoverDownloader { _, _ ->
            requests.incrementAndGet()
            CoverTransferResult.NOT_FOUND
        }
        try {
            val firstDatabase = Room.databaseBuilder(context, RankOfflineDatabase::class.java, name).build()
            try {
                val database = firstDatabase
                database.mediaCatalogDao().insertNewItems(listOf(MediaItemEntity(key.packId.value,
                    key.id, "Missing cover", provider = "fixture", remoteCoverRef = key.id)))
                val repository = CoverRepository(context, database,
                    manifestProvider = { TestFixtures.manifest(it.value) },
                    downloader = downloader, beforeCoverMutation = { _, _ -> })
                assertEquals(CoverDownloadResult.NotFound, repository.downloadCover(key))
                assertEquals(CoverResolutionState.NOT_FOUND.name,
                    database.mediaDao().item(key.packId.value, key.id)?.coverResolutionState)
            } finally { firstDatabase.close() }
            val reopenedDatabase = Room.databaseBuilder(context, RankOfflineDatabase::class.java, name).build()
            try {
                val database = reopenedDatabase
                val repository = CoverRepository(context, database,
                    manifestProvider = { TestFixtures.manifest(it.value) },
                    downloader = downloader, beforeCoverMutation = { _, _ -> })
                val snapshot = repository.observeCover(key).first()
                assertEquals(CoverResolutionState.NOT_FOUND, snapshot.resolution)
                assertFalse(CoverFrame(snapshot, null, false, false).canRequestDownload)
                assertEquals(CoverDownloadResult.NotFound, repository.downloadCover(key))
                assertEquals(1, requests.get())
            } finally { reopenedDatabase.close() }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
