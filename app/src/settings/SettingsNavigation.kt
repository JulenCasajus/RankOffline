package org.rankoffline.app

/** Navigation is UI-only; pack and settings state remains in ViewModel/repositories. */
internal enum class SettingsDestination {
    HOME, GENERAL, ACTIVE_PACK, DISPLAY, THEME, PACKS, BACKUP, RANKING_SHARE,
    COVERS, LANGUAGE, RATING, RATING_PROFILES, RATING_CATEGORIES,
    ICON, ABOUT, DONATION
}

internal fun settingsParent(screen: SettingsDestination): SettingsDestination = when (screen) {
    SettingsDestination.DISPLAY, SettingsDestination.COVERS, SettingsDestination.RATING -> SettingsDestination.ACTIVE_PACK
    SettingsDestination.PACKS, SettingsDestination.THEME, SettingsDestination.ICON,
    SettingsDestination.LANGUAGE -> SettingsDestination.GENERAL
    SettingsDestination.RANKING_SHARE -> SettingsDestination.PACKS
    SettingsDestination.RATING_PROFILES, SettingsDestination.RATING_CATEGORIES -> SettingsDestination.RATING
    else -> SettingsDestination.HOME
}
