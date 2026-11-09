package org.rankoffline.app


internal fun PreferencesUiController.icon(value: AppIcon) = run { settings.updateAppIcon(value) }


internal fun PreferencesUiController.theme(value: ThemeSelection) = run { settings.updateThemeSelection(value) }


internal fun PreferencesUiController.builtInTheme(value: AppThemePalette) = run { settings.updateThemePalette(value) }


internal fun PreferencesUiController.saveTheme(value: CustomAppPalette, select: Boolean) = run { settings.saveCustomTheme(value, select) }


internal fun PreferencesUiController.deleteTheme(id: String) = run { settings.deleteCustomTheme(id) }


internal fun PreferencesUiController.saveBuiltIn(builtIn: AppThemePalette, value: CustomAppPalette) = run {
    settings.saveBuiltInTheme(builtIn, value)
}


internal fun PreferencesUiController.deleteBuiltIn(builtIn: AppThemePalette) = run { settings.deleteBuiltInTheme(builtIn) }


internal fun PreferencesUiController.resetBuiltIns() = run { settings.resetBuiltInThemes() }
