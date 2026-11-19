package org.rankoffline.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackUpdatePersonalDataInstrumentedTest {
    @Test fun crashAfterRoomCommitRestoresOldCatalogAndRating() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "pack-update-crash-fixture")
        root.deleteRecursively()
        val database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java).build()
        val installer = PackInstaller(root)
        val oldRows = """{"id":"item:1","title":"Before"}""".toByteArray()
        val newRows = """{"id":"item:1","title":"After"}""".toByteArray()
        val oldJson = manifest(oldRows, "fixture-v1")
        val newJson = manifest(newRows, "fixture-v2")
        val old = PackManifestParser.parse(oldJson)
        try {
            val oldDir = installer.install(PackInstallRequest(oldJson)) { ByteArrayInputStream(oldRows) }
            CatalogImporter(database).importIfNeeded(old, oldDir)
            installer.commitInstallation(PackId(11))
            database.personalDataDao().upsertRatings(listOf(MediaRatingEntity(11, "item:1",
                0.0, 7.0, 7.0, "keep", "done", 1, "general")))
            val staged = installer.stageUpdate(PackInstallRequest(newJson)) {
                ByteArrayInputStream(newRows)
            }
            CatalogImporter(database).importIfNeeded(PackManifestParser.parse(newJson), staged)
            assertEquals("After", database.mediaDao().item(11, "item:1")?.title)

            val manager = PackManager(root, File(root, "cache"), PackSourceRegistry(),
                PackArchiveDownloader { _, _, _ -> error("No network in recovery test") },
                RoomPackCatalogSynchronizer(CatalogImporter(database)),
                RoomPackCatalogCleaner(database), BuildConfig.VERSION_NAME)
            assertTrue(manager.reconcileInterruptedUpdate(PackId(11)))
            assertEquals("Before", database.mediaDao().item(11, "item:1")?.title)
            assertEquals("keep", database.personalDataDao().allRatings().single().notes)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test fun failedImportRollsBackFilesAndCatalogWithoutTouchingRatings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "pack-update-failed-import")
        root.deleteRecursively()
        val database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java).build()
        val installer = PackInstaller(root)
        val oldRows = """{"id":"item:1","title":"Before"}""".toByteArray()
        val badRows = ("""{"id":"item:1","title":"After"}""" + "\n" +
            """{"id":"bad","title":"Invalid","unsupported":true}""").toByteArray()
        val oldJson = manifest(oldRows, "fixture-v1")
        val badJson = manifest(badRows, "fixture-v2")
        try {
            val directory = installer.install(PackInstallRequest(oldJson)) { ByteArrayInputStream(oldRows) }
            CatalogImporter(database).importIfNeeded(PackManifestParser.parse(oldJson), directory)
            installer.commitInstallation(PackId(11))
            database.personalDataDao().upsertRatings(listOf(MediaRatingEntity(11, "item:1",
                0.0, 7.0, 7.0, "keep", "done", 1, "general")))
            val staged = installer.stageUpdate(PackInstallRequest(badJson)) {
                ByteArrayInputStream(badRows)
            }
            assertTrue(runCatching { CatalogImporter(database).importIfNeeded(
                PackManifestParser.parse(badJson), staged) }.isFailure)
            assertEquals(PackRecoveryState.CLEAN, installer.recover(PackId(11)))
            assertEquals("fixture-v1", readInstalledManifest(installer.packDirectory(PackId(11)))?.version)
            assertEquals("Before", database.mediaDao().item(11, "item:1")?.title)
            assertEquals("keep", database.personalDataDao().allRatings().single().notes)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test fun updateAndReopenPreserveAllFourPersonalRatingTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "pack-update-personal-fixture.db"
        val root = File(context.cacheDir, "pack-update-personal-fixture")
        context.deleteDatabase(name)
        root.deleteRecursively()
        val installer = PackInstaller(root)
        // This fixture uses the same identity across both catalog revisions.
        val oldRows = """{"id":"item:1","title":"Before"}""".toByteArray()
        val newRows = ("""{"id":"item:1","title":"After"}""" + "\n" +
            """{"id":"item:2","title":"New"}""").toByteArray()
        val oldJson = manifest(oldRows, "fixture-v1")
        val newJson = manifest(newRows, "fixture-v2")
        val old = PackManifestParser.parse(oldJson)
        val updated = PackManifestParser.parse(newJson)
        val id = old.packId
        val database = Room.databaseBuilder(context, RankOfflineDatabase::class.java, name).build()
        try {
            val oldDir = installer.install(PackInstallRequest(oldJson)) { ByteArrayInputStream(oldRows) }
            CatalogImporter(database).importIfNeeded(old, oldDir)
            installer.commitInstallation(id)
            val personal = database.personalDataDao()
            personal.upsertRatings(listOf(MediaRatingEntity(11, "item:1", 0.4, 8.0, 8.4,
                "private note", "done", 15, "general")))
            personal.upsertRatingScores(listOf(MediaRatingScoreEntity(11, "item:1", "writing", 8.0, true)))
            personal.upsertGroupRatings(listOf(MediaGroupRatingEntity(11, "group:1", 0.2, 7.0, 7.2,
                "group note", "done", 16, "general")))
            personal.upsertGroupRatingScores(listOf(MediaGroupRatingScoreEntity(11, "group:1",
                "writing", 7.0, true)))
            val before = listOf(personal.allRatings(), personal.allRatingScores(),
                personal.allGroupRatings(), personal.allGroupRatingScores())

            val newDir = installer.stageUpdate(PackInstallRequest(newJson)) {
                ByteArrayInputStream(newRows)
            }
            CatalogImporter(database).importIfNeeded(updated, newDir)
            installer.commitUpdate(id)
            assertEquals("After", database.mediaDao().item(11, "item:1")?.title)
            assertEquals(2L, database.mediaCatalogDao().itemCount(11))
            assertEquals(before, listOf(personal.allRatings(), personal.allRatingScores(),
                personal.allGroupRatings(), personal.allGroupRatingScores()))
        } finally { database.close() }
        val reopened = Room.databaseBuilder(context, RankOfflineDatabase::class.java, name).build()
        try {
            assertEquals("private note", reopened.personalDataDao().allRatings().single().notes)
            assertEquals(1, reopened.personalDataDao().allRatingScores().size)
            assertEquals("group note", reopened.personalDataDao().allGroupRatings().single().notes)
            assertEquals(1, reopened.personalDataDao().allGroupRatingScores().size)
            assertTrue(installer.matchesInstalledManifest(updated))
        } finally {
            reopened.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    private fun manifest(rows: ByteArray, version: String): String =
        JSONObject(TestFixtures.manifestJson(catalog = rows))
            .put("version", version).put("slug", "fixture").toString()
}
