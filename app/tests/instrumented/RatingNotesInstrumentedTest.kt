package org.rankoffline.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RatingNotesInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun typingKeepsFocusedNotesVisibleAndSavesLatestText() {
        val id = PackId(11)
        val manifest = TestFixtures.manifest(11)
        val settings = AppSettingsState(mediaSettings = mapOf(id to manifest.resolveSettings(
            PersonalPackPreferences(elaborateRatingEnabled = true, personalTasteEnabled = true))))
        val draft = RatingDraft(finalScore = 5.0, qualityScore = 5.0)
        val editor = RatingEditorState(MediaListEntry.Item(MediaItem(id, "item", "Title")), draft)
        var saved = ""
        compose.setContent { MaterialTheme {
            RatingScreen(editor, draft, settings, onBack = {},
                onSaveSimple = { _, _, _ -> }, onSaveElaborate = { _, _, _ -> },
                onSaveMetadata = { notes, _ -> saved = notes }, onDelete = {})
        } }

        val notes = compose.onNodeWithTag("rating_notes")
        notes.performClick()
        notes.performTextInput("First")
        notes.assertIsDisplayed()
        notes.performTextReplacement("Final note")
        notes.assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        assertEquals("Final note", saved)
    }
}
