package org.rankoffline.app


fun RankOfflineViewModel.updateAppIcon(value: AppIcon) = preferences.icon(value)


fun RankOfflineViewModel.updateThemePalette(value: AppThemePalette) = preferences.builtInTheme(value)


fun RankOfflineViewModel.updateThemeSelection(value: ThemeSelection) = preferences.theme(value)


fun RankOfflineViewModel.saveCustomTheme(value: CustomAppPalette, selectAfterSave: Boolean) = preferences.saveTheme(value, selectAfterSave)


fun RankOfflineViewModel.deleteCustomTheme(id: String) = preferences.deleteTheme(id)


fun RankOfflineViewModel.saveBuiltInTheme(value: AppThemePalette, theme: CustomAppPalette) = preferences.saveBuiltIn(value, theme)


fun RankOfflineViewModel.deleteBuiltInTheme(value: AppThemePalette) = preferences.deleteBuiltIn(value)


fun RankOfflineViewModel.resetBuiltInThemes() = preferences.resetBuiltIns()


fun RankOfflineViewModel.updateLanguage(value: SupportedLanguage) = preferences.language(value)

// Covers: explicit user actions only; composables observe CoverRepository directly.
