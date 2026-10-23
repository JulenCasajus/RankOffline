package org.rankoffline.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Clears the previous editor at the start of a new pointer gesture without consuming that gesture.
 * A TextField underneath can therefore request focus normally, while buttons, chips and sliders
 * still receive the same event and the previous IME session is dismissed.
 */
internal fun Modifier.dismissFocusOnUnconsumedTap(focusManager: FocusManager): Modifier =
    pointerInput(focusManager) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            focusManager.clearFocus(force = true)
        }
    }
