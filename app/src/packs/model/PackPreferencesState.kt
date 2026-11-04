package org.rankoffline.app

import kotlinx.serialization.Serializable

/** Editable per-pack presentation choices in filesDir/local/<pack-id>/preferences.json. */
@Serializable
internal data class PersonalPackPreferences(
    val showImages: Boolean? = null,
    val stickyScore: Boolean? = null,
    val mergeEnabled: Boolean? = null,
    /** Individual items may borrow a group image, without merging catalog entries. */
    val useGroupCoversForItems: Boolean? = null,
    val elaborateRatingEnabled: Boolean? = null,
    val catalogSortOrder: CatalogSortOrder? = null
)

/** Resolve presentation preferences against the pack and its local editable rating copy. */
internal fun PackManifest.resolveSettings(
    personal: PersonalPackPreferences,
    localRating: PackRatingDefinition? = rating
): MediaSettings {
    val definition = localRating ?: rating
    return MediaSettings(
        elaborateRatingEnabled = capabilities.ratings &&
            (personal.elaborateRatingEnabled ?: (defaults.ratingMode == "elaborated")),
        ratingConfig = definition?.config ?: RatingSystemConfig(emptyList()),
        ratingProfiles = definition?.profiles ?: RatingProfilesConfig(emptyList()),
        mergeEnabled = (personal.mergeEnabled ?: defaults.mergeEnabled) && merge.enabled,
        useGroupCoversForItems = personal.useGroupCoversForItems ?: false,
        showImages = personal.showImages ?: defaults.showImages,
        stickyScore = personal.stickyScore ?: defaults.stickyScoreHeader
    )
}

/** Top-level tabs; stable names are saved in the global DataStore. */
enum class AppTab {
    SEARCH, RANKING, SETTINGS;

    companion object {
        fun fromPersistedValue(value: String?): AppTab =
            entries.firstOrNull { it.name == value } ?: SEARCH
    }
}

/** UI state; the active pack can be null when no packs are installed. */
data class AppSettingsState(
    val activePackId: PackId? = null,
    val lastViewingScope: ViewingScope = ViewingScope.All,
    val lastTab: AppTab = AppTab.SEARCH,
    val mediaSettings: Map<PackId, MediaSettings> = emptyMap(),
    val catalogSortOrders: Map<PackId, CatalogSortOrder> = emptyMap(),
    val allViewSettings: AllViewSettings = AllViewSettings(),
    val appIcon: AppIcon = AppIcon.DARK,
    val themeSelection: ThemeSelection = ThemeSelection.BuiltIn(AppThemePalette.MIDNIGHT_NEON),
    val customPalettes: List<CustomAppPalette> = emptyList(),
    val builtInPaletteOverrides: Map<AppThemePalette, CustomAppPalette> = emptyMap(),
    val hiddenBuiltInPalettes: Set<AppThemePalette> = emptySet(),
    val language: SupportedLanguage = SupportedLanguage.DEFAULT,
    val hasCompletedWelcome: Boolean = false,
    val hasCompletedOnboarding: Boolean = false,
    val settingsLoaded: Boolean = false
) {
    val activeMediaSettings: MediaSettings? get() = activePackId?.let(mediaSettings::get)
    val showImages: Boolean get() = activeMediaSettings?.showImages ?: false
    val stickyScore: Boolean get() = activeMediaSettings?.stickyScore ?: true
    val elaborateRatingEnabled: Boolean get() = activeMediaSettings?.elaborateRatingEnabled ?: false
    val ratingConfig: RatingSystemConfig get() = activeMediaSettings?.ratingConfig
        ?: RatingSystemConfig(emptyList())
    val ratingProfiles: RatingProfilesConfig get() = activeMediaSettings?.ratingProfiles
        ?: RatingProfilesConfig(emptyList())
    val mergeEnabled: Boolean get() = activeMediaSettings?.mergeEnabled ?: false
    val useGroupCoversForItems: Boolean get() = activeMediaSettings?.useGroupCoversForItems ?: false
    val themePalette: AppThemePalette
        get() = (themeSelection as? ThemeSelection.BuiltIn)?.palette ?: AppThemePalette.MIDNIGHT_NEON
    val activePalette: AppPalette get() = themeSelection.resolve(customPalettes, builtInPaletteOverrides)
}
