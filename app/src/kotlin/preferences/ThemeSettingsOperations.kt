package org.rankoffline.app

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.sync.withLock

suspend fun SettingsRepository.updateAppIcon(value: AppIcon) = lock.withLock {
    val previous = _state.value.appIcon
    persistAndActivateAppIcon(
        icon = value,
        previousIcon = previous,
        persistSelection = { icon ->
            context.dataStore.edit { it[iconKey] = icon.persistedValue }
            _state.value = _state.value.copy(appIcon = icon)
        },
        activateAlias = iconAliasController::activate
    )
}


suspend fun SettingsRepository.updateThemePalette(value: AppThemePalette) =
    updateThemeSelection(ThemeSelection.BuiltIn(value))


suspend fun SettingsRepository.updateThemeSelection(value: ThemeSelection) = lock.withLock {
    val workspace = _state.value.themeWorkspace()
    val selected = safeThemeSelection(value, workspace)
    context.dataStore.edit { it[themeKey] = selected.persistedValue }
    _state.value = _state.value.copy(themeSelection = selected)
}


suspend fun SettingsRepository.saveCustomTheme(value: CustomAppPalette, selectAfterSave: Boolean) =
    updateThemeWorkspace { it.withCustomPaletteSaved(value, selectAfterSave) }


suspend fun SettingsRepository.deleteCustomTheme(id: String) =
    updateThemeWorkspace { it.withCustomPaletteDeleted(id) }


suspend fun SettingsRepository.saveBuiltInTheme(builtIn: AppThemePalette, value: CustomAppPalette) =
    updateThemeWorkspace { it.withBuiltInPaletteSaved(builtIn, value) }


suspend fun SettingsRepository.deleteBuiltInTheme(builtIn: AppThemePalette) =
    updateThemeWorkspace { it.withBuiltInPaletteDeleted(builtIn) }


suspend fun SettingsRepository.resetBuiltInThemes() =
    updateThemeWorkspace { it.withBuiltInPalettesReset() }


internal suspend fun SettingsRepository.updateThemeWorkspace(transform: (AppSettingsState) -> AppSettingsState) = lock.withLock {
    val updated = transform(_state.value)
    context.dataStore.edit {
        it[themeWorkspaceKey] = encodeThemeWorkspace(updated.themeWorkspace())
        it[themeKey] = updated.themeSelection.persistedValue
    }
    _state.value = updated
}
