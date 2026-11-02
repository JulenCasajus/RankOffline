package org.rankoffline.app

import android.app.Application
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import java.io.File

/** Process entry point shared by activities and WorkManager-only launches. */
class RankOfflineApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installDefaultShareBackgrounds()
        RankOfflineServices.configure(PackServiceConfiguration(
            downloader = PackArchiveDownloader { _, _, _ -> PackOperationResult.NOT_FOUND },
            // Remote discovery is deliberately absent in v0.2.2. A future trusted registry
            // can replace this injection without adding provider identities to the APK.
            loadTrustedSources = { registry -> registry.replace(emptyList()) }
        ))
    }

    private fun installDefaultShareBackgrounds() {
        val directory = File(filesDir, "share-defaults")
        if (!directory.exists() && !directory.mkdirs()) return
        listOf(
            R.raw.default_share_top7 to "default_share_top7.webp",
            R.raw.default_share_top10 to "default_share_top10.webp"
        ).forEach { (resource, name) ->
            val destination = File(directory, name)
            if (!destination.isFile) {
                // Share rendering has its own procedural fallback, so a low-storage or
                // damaged-resource failure must never prevent application startup.
                runCatching {
                    resources.openRawResource(resource).use { input ->
                        destination.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }
}

/** Android entry point only. No catalog, bitmap or disk work on the main thread. */
class MainActivity : AppCompatActivity() {
    private val appViewModel: RankOfflineViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by appViewModel.settings.collectAsState()
            RankOfflineTheme(settings.activePalette) {
                RankOfflineApp(appViewModel)
            }
        }
    }
}
