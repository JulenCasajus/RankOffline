package org.rankoffline.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Executes the real WorkManager UPDATE action with a local verified ZIP fixture. */
@RunWith(AndroidJUnit4::class)
class PackUpdateWorkInstrumentedTest {
    @Test fun officialLegacyPackUpdatesThroughIndexAndWorkManager() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(Build.HARDWARE.startsWith("ranchu") || Build.HARDWARE.startsWith("goldfish"))
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        assumeTrue(connectivity.activeNetwork?.let { network ->
            connectivity.getNetworkCapabilities(network)?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true)
        val id = PackId(4)
        val root = File(context.cacheDir, "pack-update-worker-fixture")
        val databaseName = "pack-update-worker-fixture.db"
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val database = Room.databaseBuilder(context, RankOfflineDatabase::class.java, databaseName).build()
        val oldRows = """{"id":"item:1","title":"Old"}""".toByteArray()
        val newRows = """{"id":"item:1","title":"New"}""".toByteArray()
        fun manifest(rows: ByteArray, version: String, slug: String?) =
            JSONObject(TestFixtures.manifestJson(packId = id.value, catalog = rows))
                .put("version", version).apply { if (slug != null) put("slug", slug) }.toString()
        val oldJson = manifest(oldRows, "tmdb-series-2026-10-16-v3", null)
        val newJson = manifest(newRows, "series-v4", "series")
        val archive = File(root, "updated.pack")
        ZipOutputStream(archive.outputStream()).use { zip ->
            listOf("manifest.json" to newJson.toByteArray(),
                "catalog/items.jsonl" to newRows).forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val entry = JSONObject().put("packId", id.value).put("version", "series-v4")
            .put("displayName", "Fixture Pack").put("fileName", "pack-4.pack")
            .put("archiveBytes", archive.length()).put("archiveSha256", sha256Of(archive))
            .put("registryId", "rankoffline-official-4")
            .put("archiveUrl", "https://example.com/pack-4.pack")
            .put("manifest", JSONObject(newJson))
        val index = JSONObject().put("schemaVersion", 1).put("generatedBy", "test")
            .put("packs", JSONArray().put(entry))
        val sources = PackSourceRegistry().apply {
            replace(PackIndexParser.parse(index.toString(), TrustedHttpsHosts(setOf("example.com"))))
        }
        val manager = PackManager(File(root, "packs"), File(root, "cache"), sources,
            PackArchiveDownloader { _, destination, progress ->
                archive.copyTo(destination)
                progress(destination.length())
                PackOperationResult.INSTALLED
            }, RoomPackCatalogSynchronizer(CatalogImporter(database)),
            RoomPackCatalogCleaner(database), BuildConfig.VERSION_NAME)
        PackWorkerRuntimeProvider.configure { object : PackWorkerRuntime {
            override val packManager = manager
            override suspend fun prepare(packId: PackId, action: PackWorkAction) = Unit
            override suspend fun resumeInitialCoversIfNeeded(packId: PackId) = true
        } }
        try {
            val installed = manager.installer.install(PackInstallRequest(oldJson)) {
                ByteArrayInputStream(oldRows)
            }
            CatalogImporter(database).importIfNeeded(PackManifestParser.parse(oldJson), installed)
            manager.installer.commitInstallation(id)
            database.mediaDao().upsertRating(MediaRatingEntity(id.value, "item:1", 0.1, 8.0,
                8.1, "keep", "done", 1, "general"))
            manager.refresh()
            assertTrue(manager.states.value[id]?.canUpdate == true)
            PackWorkScheduler.enqueue(context, id, PackWorkAction.UPDATE)
            withTimeout(60_000) {
                while (true) {
                    val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(
                        PackWorkScheduler.uniqueName(id, PackWorkAction.UPDATE)).get()
                    if (infos.isNotEmpty() && infos.all { it.state.isFinished }) {
                        assertTrue("Update worker failed: $infos",
                            infos.all { it.state == WorkInfo.State.SUCCEEDED })
                        break
                    }
                    delay(200)
                }
            }
            assertEquals("New", database.mediaDao().item(id.value, "item:1")?.title)
            assertEquals("keep", database.mediaDao().rating(id.value, "item:1")?.notes)
            assertEquals("series-v4", readInstalledManifest(manager.installer.packDirectory(id))?.version)
        } finally {
            PackWorkScheduler.cancel(context, id)
            database.close()
            context.deleteDatabase(databaseName)
            root.deleteRecursively()
        }
    }
}
