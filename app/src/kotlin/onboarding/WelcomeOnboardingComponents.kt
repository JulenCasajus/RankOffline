package org.rankoffline.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun WelcomeDialogBackground(content: @Composable (Dp) -> Unit) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false
    )) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center) {
            val dialogHeight = (maxHeight - 48.dp).coerceAtLeast(200.dp)
            Surface(color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(0.9f).widthIn(max = 520.dp)
                    .heightIn(max = dialogHeight)) {
                content(dialogHeight)
            }
        }
    }
}
