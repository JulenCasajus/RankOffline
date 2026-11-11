package org.rankoffline.app

import androidx.activity.compose.*
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
import java.util.UUID

@Composable
internal fun ThemeSettingsScreen(
    selectedTheme: ThemeSelection,
    customPalettes: List<CustomAppPalette>,
    builtInOverrides: Map<AppThemePalette, CustomAppPalette>,
    hiddenBuiltIns: Set<AppThemePalette>,
    activePalette: AppPalette,
    onThemeSelected: (ThemeSelection) -> Unit,
    onSaveCustomTheme: (CustomAppPalette, Boolean) -> Unit,
    onDeleteCustomTheme: (String) -> Unit,
    onSaveBuiltInTheme: (AppThemePalette, CustomAppPalette) -> Unit,
    onDeleteBuiltInTheme: (AppThemePalette) -> Unit,
    onResetBuiltInThemes: () -> Unit,
    onBack: () -> Unit
) {
    var editor by remember { mutableStateOf<CustomThemeEditorState?>(null) }
    BackHandler(enabled = editor != null) { editor = null }

    editor?.let { state ->
        CustomThemeEditorScreen(
            initial = state.palette,
            isNew = state.isNew,
            canDelete = AppThemePalette.entries.size - hiddenBuiltIns.size + customPalettes.size > 1,
            onSave = { value ->
                state.builtInSource?.let { onSaveBuiltInTheme(it, value) } ?: run {
                    val wasSelected = (selectedTheme as? ThemeSelection.Custom)?.id == value.id
                    onSaveCustomTheme(value, state.isNew || wasSelected)
                }
                editor = null
            },
            onDelete = { value ->
                state.builtInSource?.let(onDeleteBuiltInTheme) ?: onDeleteCustomTheme(value.id)
                editor = null
            },
            onBack = { editor = null }
        )
        return
    }

    SettingsSubscreen(stringResource(R.string.settings_theme_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = floatingNavigationContentPadding(bottom = 24.dp)
        ) {
            items(
                AppThemePalette.entries.filterNot { it in hiddenBuiltIns },
                key = { it.persistedValue }
            ) { palette ->
                val override = builtInOverrides[palette]
                val displayedName = override?.name ?: stringResource(palette.displayNameResource)
                val displayedPalette = override?.palette ?: palette.palette()
                ThemeOptionCard(
                    name = displayedName,
                    palette = displayedPalette,
                    selected = selectedTheme == ThemeSelection.BuiltIn(palette),
                    onSelect = { onThemeSelected(ThemeSelection.BuiltIn(palette)) },
                    onEdit = {
                        editor = CustomThemeEditorState(
                            palette = CustomAppPalette(
                                id = palette.persistedValue,
                                name = displayedName,
                                palette = displayedPalette
                            ),
                            isNew = false,
                            builtInSource = palette
                        )
                    }
                )
            }
            items(customPalettes, key = CustomAppPalette::id) { custom ->
                ThemeOptionCard(
                    name = custom.name,
                    palette = custom.palette,
                    selected = selectedTheme == ThemeSelection.Custom(custom.id),
                    onSelect = { onThemeSelected(ThemeSelection.Custom(custom.id)) },
                    onEdit = { editor = CustomThemeEditorState(custom, isNew = false) }
                )
            }
            item {
                SettingsActionButton(
                    onClick = {
                        editor = CustomThemeEditorState(
                            palette = CustomAppPalette(
                                id = UUID.randomUUID().toString(),
                                name = "",
                                palette = activePalette
                            ),
                            isNew = true,
                            builtInSource = null
                        )
                    },
                    label = stringResource(R.string.theme_create),
                    icon = Icons.Default.Add,
                    primary = true
                )
            }
            item {
                SettingsActionButton(
                    onClick = onResetBuiltInThemes,
                    label = stringResource(R.string.theme_reset_builtins),
                    icon = Icons.Default.Restore,
                    primary = false
                )
            }
        }
    }
}

internal data class CustomThemeEditorState(
    val palette: CustomAppPalette,
    val isNew: Boolean,
    val builtInSource: AppThemePalette? = null
)

@Composable
private fun ThemeOptionCard(
    name: String,
    palette: AppPalette,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { if (!selected) onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PaletteSwatches(palette)
            Spacer(Modifier.width(16.dp))
            Text(name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            onEdit?.let { edit ->
                IconButton(onClick = edit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.theme_edit))
                }
            }
            RadioButton(selected = selected, onClick = { if (!selected) onSelect() })
        }
    }
}

@Composable
private fun PaletteSwatches(palette: AppPalette) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(
            palette.background,
            palette.surface,
            palette.primary,
            palette.secondary
        ).forEach { color ->
            Box(
                Modifier
                    .size(width = 16.dp, height = 36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color)
            )
        }
    }
}
