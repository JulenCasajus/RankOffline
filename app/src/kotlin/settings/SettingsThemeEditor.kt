package org.rankoffline.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*

private enum class ThemeColorSlot {
    BACKGROUND,
    SURFACE,
    PRIMARY,
    SECONDARY
}


@Composable
internal fun CustomThemeEditorScreen(
    initial: CustomAppPalette,
    isNew: Boolean,
    canDelete: Boolean,
    onSave: (CustomAppPalette) -> Unit,
    onDelete: (CustomAppPalette) -> Unit,
    onBack: () -> Unit
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var palette by remember(initial.id) { mutableStateOf(initial.palette) }
    var editingColor by remember(initial.id) { mutableStateOf<ThemeColorSlot?>(null) }
    var confirmDelete by remember(initial.id) { mutableStateOf(false) }
    val normalizedName = name.trim()

    SettingsSubscreen(
        stringResource(if (isNew) R.string.theme_create else R.string.theme_edit),
        onBack
    ) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = floatingNavigationContentPadding(bottom = 24.dp)
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= MAX_CUSTOM_THEME_NAME_LENGTH) name = it },
                    label = { Text(stringResource(R.string.theme_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item { CustomThemePreview(palette) }
            items(ThemeColorSlot.entries, key = { it.name }) { slot ->
                ThemeColorRow(
                    label = stringResource(slot.labelResource()),
                    color = palette.color(slot),
                    onClick = { editingColor = slot }
                )
            }
            item {
                Button(
                    enabled = normalizedName.isNotEmpty(),
                    onClick = {
                        onSave(initial.copy(name = normalizedName, palette = palette))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
            if (!isNew && canDelete) {
                item {
                    TextButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.theme_delete))
                    }
                }
            }
        }
    }

    editingColor?.let { slot ->
        RankOfflineColorPickerDialog(
            initialColor = HsvColor.fromArgb(palette.color(slot).toArgb()).toHex(),
            onDismiss = { editingColor = null },
            onApply = { hex ->
                palette = palette.withColor(slot, Color(android.graphics.Color.parseColor(hex)))
                editingColor = null
            }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(
                    stringResource(
                        R.string.theme_delete_confirmation_title,
                        normalizedName.ifEmpty { initial.name }
                    )
                )
            },
            text = { Text(stringResource(R.string.theme_delete_confirmation_body)) },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(initial)
                }) {
                    Text(stringResource(R.string.theme_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

@Composable
private fun CustomThemePreview(palette: AppPalette) {
    MaterialTheme(colorScheme = palette.toColorScheme()) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.theme_preview),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.theme_preview_card),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            ).forEach { color ->
                                Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(color))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeColorRow(label: String, color: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(color)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp))
            )
        }
    }
}

private fun ThemeColorSlot.labelResource(): Int = when (this) {
    ThemeColorSlot.BACKGROUND -> R.string.theme_color_background
    ThemeColorSlot.SURFACE -> R.string.theme_color_surface
    ThemeColorSlot.PRIMARY -> R.string.theme_color_primary
    ThemeColorSlot.SECONDARY -> R.string.theme_color_secondary
}

private fun AppPalette.color(slot: ThemeColorSlot): Color = when (slot) {
    ThemeColorSlot.BACKGROUND -> background
    ThemeColorSlot.SURFACE -> surface
    ThemeColorSlot.PRIMARY -> primary
    ThemeColorSlot.SECONDARY -> secondary
}

private fun AppPalette.withColor(slot: ThemeColorSlot, color: Color): AppPalette = when (slot) {
    ThemeColorSlot.BACKGROUND -> copy(background = color)
    ThemeColorSlot.SURFACE -> copy(surface = color)
    ThemeColorSlot.PRIMARY -> copy(primary = color)
    ThemeColorSlot.SECONDARY -> copy(secondary = color)
}
