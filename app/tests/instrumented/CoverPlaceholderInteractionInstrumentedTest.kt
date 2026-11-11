package org.rankoffline.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CoverPlaceholderInteractionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun confirmedAbsenceHasNoButtonAction() {
        var requests = 0
        compose.setContent {
            MaterialTheme {
                CoverPlaceholder(Modifier.testTag("cover"), loading = false,
                    notFound = true, enabled = false, onClick = { requests++ })
            }
        }
        compose.onNodeWithTag("cover", useUnmergedTree = true).assertHasNoClickAction()
        assertEquals(0, requests)
    }

    @Test fun unknownCoverRemainsDownloadable() {
        var requests = 0
        compose.setContent {
            MaterialTheme {
                CoverPlaceholder(Modifier.testTag("cover"), loading = false,
                    notFound = false, enabled = true, onClick = { requests++ })
            }
        }
        compose.onNodeWithTag("cover").assertHasClickAction().performClick()
        assertEquals(1, requests)
    }
}
