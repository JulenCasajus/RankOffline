package org.rankoffline.app

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Releases text-input focus when the user taps an otherwise non-interactive
 * area of this container.
 *
 * Unlike a pointer-down listener, this waits for a completed tap and respects
 * descendant gesture consumption (TextFields, buttons, sliders, scrolling).
 * Apply to a screen/form container, not directly to a TextField.
 *
 * Interactive child controls that should dismiss the keyboard on click should
 * do so explicitly in their onClick handler.
 */
internal fun Modifier.clearFocusOnBackgroundTap(focusManager: FocusManager): Modifier =
    pointerInput(focusManager) {
        detectTapGestures(onTap = { focusManager.clearFocus() })
    }
