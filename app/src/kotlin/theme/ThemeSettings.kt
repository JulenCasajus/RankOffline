package org.rankoffline.app

/** Pure theme transitions: no DataStore, Android or catalog dependencies. */

internal fun AppSettingsState.withCustomPaletteSaved(
    value: CustomAppPalette,
    selectAfterSave: Boolean
): AppSettingsState {
    val updatedPalettes = customPalettes.upsert(value)
    val updatedSelection = if (
        selectAfterSave || (themeSelection as? ThemeSelection.Custom)?.id == value.id
    ) {
        ThemeSelection.Custom(value.id)
    } else {
        themeSelection
    }
    return copy(customPalettes = updatedPalettes, themeSelection = updatedSelection)
}

internal fun AppSettingsState.withCustomPaletteDeleted(id: String): AppSettingsState {
    if (customPalettes.count { it.id != id } +
        AppThemePalette.entries.count { it !in hiddenBuiltInPalettes } == 0
    ) return this
    val updatedSelection = if ((themeSelection as? ThemeSelection.Custom)?.id == id) {
        AppThemePalette.entries.firstOrNull { it !in hiddenBuiltInPalettes }
            ?.let { ThemeSelection.BuiltIn(it) }
            ?: customPalettes.firstOrNull { it.id != id }?.let { ThemeSelection.Custom(it.id) }
            ?: ThemeSelection.BuiltIn(AppThemePalette.MIDNIGHT_NEON)
    } else {
        themeSelection
    }
    return copy(
        customPalettes = customPalettes.filterNot { it.id == id },
        themeSelection = updatedSelection
    )
}

internal fun AppSettingsState.withBuiltInPaletteSaved(
    builtIn: AppThemePalette,
    value: CustomAppPalette
): AppSettingsState {
    val normalized = listOf<CustomAppPalette>().upsert(
        value.copy(id = builtIn.persistedValue)
    ).single()
    return copy(
        builtInPaletteOverrides = builtInPaletteOverrides + (builtIn to normalized),
        hiddenBuiltInPalettes = hiddenBuiltInPalettes - builtIn
    )
}

internal fun AppSettingsState.withBuiltInPaletteDeleted(
    builtIn: AppThemePalette
): AppSettingsState {
    val remainingBuiltIns = AppThemePalette.entries.filter {
        it != builtIn && it !in hiddenBuiltInPalettes
    }
    val remainingThemeCount = remainingBuiltIns.size + customPalettes.size
    if (remainingThemeCount == 0) return this
    val updatedSelection = if ((themeSelection as? ThemeSelection.BuiltIn)?.palette == builtIn) {
        remainingBuiltIns.firstOrNull()?.let { ThemeSelection.BuiltIn(it) }
            ?: ThemeSelection.Custom(customPalettes.first().id)
    } else {
        themeSelection
    }
    return copy(
        themeSelection = updatedSelection,
        builtInPaletteOverrides = builtInPaletteOverrides - builtIn,
        hiddenBuiltInPalettes = hiddenBuiltInPalettes + builtIn
    )
}

internal fun AppSettingsState.withBuiltInPalettesReset(): AppSettingsState = copy(
    builtInPaletteOverrides = emptyMap(),
    hiddenBuiltInPalettes = emptySet()
)

internal fun AppSettingsState.themeWorkspace() = ThemeWorkspace(
    customPalettes = customPalettes,
    builtInOverrides = builtInPaletteOverrides,
    hiddenBuiltIns = hiddenBuiltInPalettes
)
