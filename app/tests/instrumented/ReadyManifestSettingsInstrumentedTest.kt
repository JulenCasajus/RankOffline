package org.rankoffline.app

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reproduces a READY state arriving before the settings manifest observer. */
@RunWith(AndroidJUnit4::class)
class ReadyManifestSettingsInstrumentedTest {
    @Test fun whatsNewAcknowledgementSurvivesRestart() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = File(app.cacheDir, "whats-new-settings-test")
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        try {
            val first = SettingsRepository(isolated,
                object : AppIconAliasController {
                    override fun activate(icon: AppIcon, previousIcon: AppIcon) = Unit
                }, AppLocaleController { })
            first.loadInitialState()
            first.completeOnboarding()
            assertTrue(shouldShowWhatsNew(first.state.value, "0.2.2-debug.4"))
            first.acknowledgeWhatsNew()
            assertFalse(shouldShowWhatsNew(first.state.value, "0.2.2-debug.4"))

            val restarted = SettingsRepository(isolated,
                object : AppIconAliasController {
                    override fun activate(icon: AppIcon, previousIcon: AppIcon) = Unit
                }, AppLocaleController { })
            restarted.loadInitialState()
            assertFalse(shouldShowWhatsNew(restarted.state.value, "0.2.2"))
        } finally { root.deleteRecursively() }
    }

    @Test fun newlyReadyManifestLoadsItsSettingsWithoutOverwritingLocalPreferences() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = File(app.cacheDir, "ready-manifest-settings-test")
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        try {
            val repository = SettingsRepository(isolated,
                object : AppIconAliasController {
                    override fun activate(icon: AppIcon, previousIcon: AppIcon) = Unit
                }, AppLocaleController { })
            val manifest = PackManifestParser.parse(TestFixtures.manifestJson(packId = 77))
            val id = manifest.packId
            assertTrue(id !in repository.manifests)

            repository.ensureReadyManifestsLoaded(listOf(manifest))
            assertEquals(manifest, repository.manifests[id])
            assertTrue(id in repository.loadedPackIds)
            assertTrue(id in repository.state.value.mediaSettings)
            val file = File(root, "local/77/preferences.json")
            val original = file.readBytes()

            repository.ensureReadyManifestsLoaded(listOf(manifest))
            assertArrayEquals(original, file.readBytes())

            repository.updateActivePack(id)
            assertFalse(repository.state.value.personalTasteEnabled)
            repository.updatePersonalTasteEnabled(true)
            assertTrue(repository.state.value.personalTasteEnabled)
            val reloaded = SettingsRepository(isolated,
                object : AppIconAliasController {
                    override fun activate(icon: AppIcon, previousIcon: AppIcon) = Unit
                }, AppLocaleController { })
            reloaded.ensureReadyManifestsLoaded(listOf(manifest))
            reloaded.updateActivePack(id)
            assertTrue(reloaded.state.value.personalTasteEnabled)

            val database = Room.inMemoryDatabaseBuilder(app, RankOfflineDatabase::class.java).build()
            try {
                val ratings = RatingRepository(database)
                val key = CatalogEntryKey(id, "rated", CatalogEntryKind.ITEM)
                ratings.saveSimpleRating(key, RatingDraft(additiveScore = 0.8), 8.0, 8.8)
                reloaded.updatePersonalTasteEnabled(false)
                val existing = ratings.loadRating(key)
                assertEquals(8.8, existing.finalScore!!, 0.0)
                assertEquals(0.8, existing.additiveScore, 0.0)
                ratings.saveSimpleRating(key, existing.forPersonalTaste(false), 8.0, 8.0)
                val updated = ratings.loadRating(key)
                assertEquals(8.0, updated.finalScore!!, 0.0)
                assertEquals(0.0, updated.additiveScore, 0.0)
            } finally { database.close() }
        } finally {
            root.deleteRecursively()
        }
    }
}
