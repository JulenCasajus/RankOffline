package org.rankoffline.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ColorPickerUiTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun singleTapUpdatesSaturationValueAndApplyReturnsIt() {
        var applied: String? = null
        composeRule.setContent {
            RankOfflineTheme(AppThemePalette.MIDNIGHT_NEON) {
                RankOfflineColorPickerDialog(
                    initialColor = "#FF0000",
                    onDismiss = {},
                    onApply = { applied = it }
                )
            }
        }

        composeRule.onNodeWithTag("color_picker_saturation_value").performTouchInput {
            click(Offset(centerX, centerY))
        }
        composeRule.onNodeWithText(context.getString(R.string.action_apply)).performClick()
        composeRule.runOnIdle {
            assertNotEquals("#FF0000", applied)
            assertEquals(7, applied?.length)
        }
    }

    @Test
    fun invalidPartialHexDoesNotReplaceLastValidColor() {
        var applied: String? = null
        composeRule.setContent {
            RankOfflineTheme(AppThemePalette.MIDNIGHT_NEON) {
                RankOfflineColorPickerDialog(
                    initialColor = "#336699",
                    onDismiss = {},
                    onApply = { applied = it }
                )
            }
        }

        composeRule.onNodeWithTag("color_picker_hex").performTextClearance()
        composeRule.onNodeWithTag("color_picker_hex").performTextInput("#12")
        composeRule.onNodeWithText(context.getString(R.string.action_apply)).performClick()
        composeRule.runOnIdle { assertNull(applied) }

        composeRule.onNodeWithTag("color_picker_hex").performTextClearance()
        composeRule.onNodeWithTag("color_picker_hex").performTextInput("#ABCDEF")
        composeRule.onNodeWithText(context.getString(R.string.action_apply)).performClick()
        composeRule.runOnIdle { assertEquals("#ABCDEF", applied) }
    }
}
