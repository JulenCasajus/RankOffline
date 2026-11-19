package org.rankoffline.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackSettingsPurgeInstrumentedTest {
    @Test fun purgeDeletesOnlySelectedPackPreferencesAndRatingConfiguration() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = File(app.cacheDir, "pack-settings-purge-test")
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        try {
            val settings = SettingsRepository(isolated)
            val first = PackId(1)
            val second = PackId(2)
            for (id in listOf(first, second)) {
                settings.packFiles.write(id, PersonalPackPreferences(showImages = false))
                settings.ratingFiles.write(id, TestFixtures.ratingDefinition())
            }
            settings.personal = mapOf(first to PersonalPackPreferences(), second to PersonalPackPreferences())
            settings.localRatings = mapOf(first to TestFixtures.ratingDefinition(),
                second to TestFixtures.ratingDefinition())

            settings.purgePackSettings(first)

            assertFalse(settings.packFiles.path(first).exists())
            assertFalse(settings.ratingFiles.path(first).exists())
            assertTrue(settings.packFiles.path(second).isFile)
            assertTrue(settings.ratingFiles.path(second).isFile)
            assertEquals(setOf(second), settings.personal.keys)
            assertEquals(setOf(second), settings.localRatings.keys)
        } finally { root.deleteRecursively() }
    }
}
