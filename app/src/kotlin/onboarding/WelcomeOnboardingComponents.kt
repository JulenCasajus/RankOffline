package org.rankoffline.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun WelcomeDialogBackground(content: @Composable () -> Unit) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false
    )) {
        Surface(color = MaterialTheme.colorScheme.background,
            shape = RectangleShape, tonalElevation = 0.dp,
            modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                content()
            }
        }
    }
}
