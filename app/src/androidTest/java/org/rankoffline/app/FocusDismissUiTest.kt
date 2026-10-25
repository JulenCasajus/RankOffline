package org.rankoffline.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FocusDismissUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun tapsTransferOrClearFocusWithoutBreakingButtons() {
        var first by mutableStateOf("")
        var second by mutableStateOf("")
        var clicks = 0
        composeRule.setContent {
            val focusManager = LocalFocusManager.current
            Column(
                Modifier.fillMaxSize()
                    .dismissFocusOnUnconsumedTap(focusManager)
                    .testTag("focus_root")
            ) {
                OutlinedTextField(
                    first,
                    { first = it },
                    modifier = Modifier.testTag("first_field")
                )
                OutlinedTextField(
                    second,
                    { second = it },
                    modifier = Modifier.testTag("second_field")
                )
                Button(
                    onClick = { clicks += 1 },
                    modifier = Modifier.testTag("focus_button")
                ) { Text("Action") }
                Spacer(Modifier.height(200.dp).testTag("focus_outside"))
            }
        }

        composeRule.onNodeWithTag("first_field").performClick().assertIsFocused()
        composeRule.onNodeWithTag("first_field").performClick().assertIsFocused()
        composeRule.onNodeWithTag("second_field").performClick().assertIsFocused()
        composeRule.onNodeWithTag("first_field").assertIsNotFocused()
        composeRule.onNodeWithTag("focus_button").performClick()
        composeRule.onNodeWithTag("second_field").assertIsNotFocused()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }
}
