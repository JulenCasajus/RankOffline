package org.rankoffline.app

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PackUpdateDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun updateUsesPackTitleAndCloudAction() {
        val id = PackId(1)
        val base = TestFixtures.manifest(1)
        val manifest = base.copy(
            displayName = "Animes", version = "animes-v7",
            presentation = base.presentation.copy(
                names = mapOf("en" to "Animes"), accentColor = "#1976D2"))
        val state = PackState(id, manifest, sourceAvailable = true, installed = true,
            status = PackRuntimeStatus.READY, archiveDownloadBytes = null,
            storage = PackStorageSnapshot(0, 0), updateVersion = "animes-v8")
        var updates = 0
        compose.setContent { MaterialTheme {
            PackUpdateDialog(listOf(id to "animes-v8"), mapOf(id to state),
                onUpdate = { updates++ }, onLater = {})
        } }

        compose.onNodeWithText("RankOffline · Animes").assertIsDisplayed()
        compose.onNodeWithText("Update").assertDoesNotExist()
        val screen = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics
        val bounds = compose.onNodeWithTag("pack_update_dialog").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width < screen.widthPixels * 0.95f)
        assertTrue(bounds.height < screen.heightPixels * 0.9f)
        compose.onNodeWithContentDescription("Update").performClick()
        assertEquals(1, updates)
    }
}
