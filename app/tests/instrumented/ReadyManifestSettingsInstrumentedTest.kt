package org.rankoffline.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reproduces a READY state arriving before the settings manifest observer. */
@RunWith(AndroidJUnit4::class)
class ReadyManifestSettingsInstrumentedTest {
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
        } finally {
            root.deleteRecursively()
        }
    }
}
