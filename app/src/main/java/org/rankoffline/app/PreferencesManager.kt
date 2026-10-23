package org.rankoffline.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

private val Context.dataStore by preferencesDataStore(name = "app_preferences")
private val settingsJson = Json { ignoreUnknownKeys = true }

@Serializable
data class RatingCategory(
    val id: String,
    val name: String,
    val description: String,
    val weight: Double,
    val color: String,
    val order: Int,
    val active: Boolean = true
)

@Serializable
data class AdditiveBonus(
    val name: String,
    val description: String,
    val maxValue: Double,
    val color: String
)

@Serializable
data class RatingSystemConfig(
    val categories: List<RatingCategory>,
    val additiveBonus: AdditiveBonus?
)

@Serializable
data class RatingProfile(
    val id: String,
    val name: String,
    val weights: Map<String, Double>
)

@Serializable
data class RatingProfilesConfig(
    val profiles: List<RatingProfile>
)

@Serializable
data class MediaSettings(
    val elaborateRatingEnabled: Boolean,
    val ratingConfig: RatingSystemConfig,
    val ratingProfiles: RatingProfilesConfig,
    val mergeEnabled: Boolean,
    val oneCoverPerGroup: Boolean = false
)

@Serializable
internal data class PersistedMediaSettings(
    val media: Map<String, MediaSettings> = emptyMap()
)

object DefaultSettings {
    const val GENERAL_PROFILE_ID = "general"

    private val animeCategories = listOf(
        RatingCategory("writing", "Writing", "Plot, themes, pacing, highlights, beginning, ending", 35.0, "#FFAA55", 0),
        RatingCategory("characters", "Characters", "Main characters, supporting characters, antagonist, development, depth, charisma, dynamics", 20.0, "#4FC3F7", 1),
        RatingCategory("visuals", "Visuals", "Animation, art style, cinematography, backgrounds, effects, character design", 20.0, "#E04BCF", 2),
        RatingCategory("worldbuilding", "Worldbuilding", "Setting, lore, power system", 15.0, "#81C784", 3),
        RatingCategory("audio", "Audio", "OST, openings/endings, sound effects", 10.0, "#EF5350", 4)
    )

    private val mangaCategories = listOf(
        RatingCategory("writing", "Writing", "Plot, themes, pacing, structure, dialogue, beginning and ending.", 30.0, "#FFAA55", 0),
        RatingCategory("characters", "Characters", "Main and supporting characters, development, depth, charisma and dynamics.", 20.0, "#4FC3F7", 1),
        RatingCategory("art", "Art", "Drawing quality, art style, character design, backgrounds and visual identity.", 25.0, "#FFAA55", 2),
        RatingCategory("paneling", "Paneling", "Page composition, panel flow, visual clarity and storytelling through layout.", 10.0, "#7EC7FF", 3),
        RatingCategory("worldbuilding", "Worldbuilding", "Setting, lore, internal rules, systems and sense of place.", 15.0, "#81C784", 4)
    )

    private val bookCategories = listOf(
        RatingCategory("writing", "Writing", "Plot, themes, ideas, dialogue, narrative construction and ending.", 30.0, "#FFAA55", 0),
        RatingCategory("characters", "Characters", "Main and supporting characters, development, depth and relationships.", 20.0, "#4FC3F7", 1),
        RatingCategory("prose", "Prose / Style", "Language, voice, readability, imagery and stylistic execution.", 25.0, "#FFAA55", 2),
        RatingCategory("worldbuilding", "Worldbuilding", "Setting, lore, internal coherence and sense of place where applicable.", 15.0, "#81C784", 3),
        RatingCategory("structure", "Pacing / Structure", "Narrative rhythm, chapter/section structure, progression and organization.", 10.0, "#7EC7FF", 4)
    )

    private val seriesCategories = listOf(
        RatingCategory("writing", "Writing", "Plot, themes, dialogue, pacing, season structure and narrative payoff.", 25.0, "#FFAA55", 0),
        RatingCategory("characters", "Characters", "Main and supporting characters, development, depth, charisma and dynamics.", 20.0, "#4FC3F7", 1),
        RatingCategory("direction", "Direction", "Staging, shot choices, performances, tone and overall audiovisual direction.", 15.0, "#FFAA55", 2),
        RatingCategory("visuals", "Visuals", "Cinematography, production design, effects, lighting and visual execution.", 15.0, "#E04BCF", 3),
        RatingCategory("worldbuilding", "Worldbuilding", "Setting, lore, internal rules and continuity where applicable.", 15.0, "#81C784", 4),
        RatingCategory("audio", "Audio", "Music, sound design, mixing and use of audio.", 10.0, "#EF5350", 5)
    )

    private val movieCategories = listOf(
        RatingCategory("writing", "Writing", "Plot, themes, dialogue, narrative construction and payoff.", 20.0, "#FFAA55", 0),
        RatingCategory("characters", "Characters", "Character writing, performances as characters, development and dynamics.", 15.0, "#4FC3F7", 1),
        RatingCategory("direction", "Direction", "Staging, performances, tone, shot choices and overall directorial execution.", 20.0, "#FFAA55", 2),
        RatingCategory("visuals", "Visuals", "Cinematography, production design, effects, lighting and visual identity.", 20.0, "#E04BCF", 3),
        RatingCategory("editing", "Editing / Pacing", "Cutting, rhythm, scene transitions, structure and use of runtime.", 15.0, "#62D6C8", 4),
        RatingCategory("audio", "Audio", "Score, music, sound design, mixing and audiovisual integration.", 10.0, "#EF5350", 5)
    )

    val defaultAdditiveBonus = AdditiveBonus("Personal taste", "How much you liked it personally, independent of technical quality", 1.0, "#E04BCF")

    private data class ProfilePreset(val id: String, val name: String, val weights: Map<String, Double>)

    private fun preset(id: String, name: String, vararg weights: Pair<String, Double>) =
        ProfilePreset(id, name, linkedMapOf(*weights))

    private val profilePresets = mapOf(
        MediaType.ANIME to listOf(
            preset("general", "General", "writing" to 35.0, "characters" to 20.0, "visuals" to 20.0, "worldbuilding" to 15.0, "audio" to 10.0),
            preset("narrative", "Narrative", "writing" to 45.0, "characters" to 25.0, "visuals" to 10.0, "worldbuilding" to 10.0, "audio" to 10.0),
            preset("character_driven", "Character-driven", "writing" to 25.0, "characters" to 40.0, "visuals" to 15.0, "worldbuilding" to 10.0, "audio" to 10.0),
            preset("visual_action", "Visual / Action", "writing" to 20.0, "characters" to 15.0, "visuals" to 40.0, "worldbuilding" to 15.0, "audio" to 10.0),
            preset("world_adventure", "World / Adventure", "writing" to 20.0, "characters" to 20.0, "visuals" to 15.0, "worldbuilding" to 35.0, "audio" to 10.0),
            preset("atmospheric", "Atmospheric", "writing" to 25.0, "characters" to 20.0, "visuals" to 20.0, "worldbuilding" to 15.0, "audio" to 20.0)
        ),
        MediaType.MANGA to listOf(
            preset("general", "General", "writing" to 30.0, "characters" to 20.0, "art" to 25.0, "paneling" to 10.0, "worldbuilding" to 15.0),
            preset("narrative", "Narrative", "writing" to 45.0, "characters" to 25.0, "art" to 10.0, "paneling" to 10.0, "worldbuilding" to 10.0),
            preset("character_driven", "Character-driven", "writing" to 25.0, "characters" to 40.0, "art" to 15.0, "paneling" to 10.0, "worldbuilding" to 10.0),
            preset("art_focused", "Art-focused", "writing" to 15.0, "characters" to 15.0, "art" to 40.0, "paneling" to 20.0, "worldbuilding" to 10.0),
            preset("world_adventure", "World / Adventure", "writing" to 20.0, "characters" to 20.0, "art" to 15.0, "paneling" to 10.0, "worldbuilding" to 35.0),
            preset("atmospheric", "Atmospheric", "writing" to 25.0, "characters" to 15.0, "art" to 30.0, "paneling" to 15.0, "worldbuilding" to 15.0)
        ),
        MediaType.BOOK to listOf(
            preset("general", "General", "writing" to 30.0, "characters" to 20.0, "prose" to 25.0, "worldbuilding" to 15.0, "structure" to 10.0),
            preset("narrative", "Narrative", "writing" to 45.0, "characters" to 20.0, "prose" to 15.0, "worldbuilding" to 10.0, "structure" to 10.0),
            preset("character_driven", "Character-driven", "writing" to 25.0, "characters" to 40.0, "prose" to 15.0, "worldbuilding" to 10.0, "structure" to 10.0),
            preset("prose_style", "Prose / Style", "writing" to 20.0, "characters" to 10.0, "prose" to 45.0, "worldbuilding" to 10.0, "structure" to 15.0),
            preset("world_adventure", "World / Adventure", "writing" to 20.0, "characters" to 15.0, "prose" to 15.0, "worldbuilding" to 40.0, "structure" to 10.0),
            preset("atmospheric", "Atmospheric", "writing" to 20.0, "characters" to 15.0, "prose" to 35.0, "worldbuilding" to 20.0, "structure" to 10.0)
        ),
        MediaType.SERIES to listOf(
            preset("general", "General", "writing" to 25.0, "characters" to 20.0, "direction" to 15.0, "visuals" to 15.0, "worldbuilding" to 15.0, "audio" to 10.0),
            preset("narrative", "Narrative", "writing" to 40.0, "characters" to 25.0, "direction" to 10.0, "visuals" to 5.0, "worldbuilding" to 10.0, "audio" to 10.0),
            preset("character_driven", "Character-driven", "writing" to 20.0, "characters" to 40.0, "direction" to 10.0, "visuals" to 10.0, "worldbuilding" to 10.0, "audio" to 10.0),
            preset("visual_direction", "Visual / Direction", "writing" to 10.0, "characters" to 10.0, "direction" to 30.0, "visuals" to 30.0, "worldbuilding" to 10.0, "audio" to 10.0),
            preset("world_adventure", "World / Adventure", "writing" to 20.0, "characters" to 15.0, "direction" to 10.0, "visuals" to 10.0, "worldbuilding" to 35.0, "audio" to 10.0),
            preset("atmospheric", "Atmospheric", "writing" to 15.0, "characters" to 15.0, "direction" to 20.0, "visuals" to 20.0, "worldbuilding" to 10.0, "audio" to 20.0)
        ),
        MediaType.MOVIE to listOf(
            preset("general", "General", "writing" to 20.0, "characters" to 15.0, "direction" to 20.0, "visuals" to 20.0, "editing" to 15.0, "audio" to 10.0),
            preset("narrative", "Narrative", "writing" to 40.0, "characters" to 25.0, "direction" to 15.0, "visuals" to 5.0, "editing" to 10.0, "audio" to 5.0),
            preset("character_driven", "Character-driven", "writing" to 25.0, "characters" to 40.0, "direction" to 15.0, "visuals" to 5.0, "editing" to 10.0, "audio" to 5.0),
            preset("visual_direction", "Visual / Direction", "writing" to 10.0, "characters" to 10.0, "direction" to 30.0, "visuals" to 30.0, "editing" to 10.0, "audio" to 10.0),
            preset("technical_craft", "Technical Craft", "writing" to 10.0, "characters" to 10.0, "direction" to 20.0, "visuals" to 20.0, "editing" to 25.0, "audio" to 15.0),
            preset("atmospheric", "Atmospheric", "writing" to 15.0, "characters" to 10.0, "direction" to 20.0, "visuals" to 20.0, "editing" to 10.0, "audio" to 25.0)
        )
    )

    private val categoriesByMedia = mapOf(
        MediaType.ANIME to animeCategories,
        MediaType.MANGA to mangaCategories,
        MediaType.SERIES to seriesCategories,
        MediaType.MOVIE to movieCategories,
        MediaType.BOOK to bookCategories
    )

    fun ratingConfig(mediaType: MediaType): RatingSystemConfig = RatingSystemConfig(
        categories = categoriesByMedia.getValue(mediaType).map(RatingCategory::copy),
        additiveBonus = defaultAdditiveBonus.copy()
    )

    fun profiles(
        mediaType: MediaType,
        categories: List<RatingCategory> = ratingConfig(mediaType).categories
    ): RatingProfilesConfig {
        val categoryIds = categories.map { it.id }
        return RatingProfilesConfig(
            profilePresets.getValue(mediaType).map { preset ->
                RatingProfile(
                    preset.id,
                    preset.name,
                    categoryIds.associateWith { preset.weights[it] ?: 0.0 }.ensureAtLeastOneWeight()
                )
            }
        )
    }

    fun mediaSettings(mediaType: MediaType): MediaSettings {
        val config = ratingConfig(mediaType)
        return MediaSettings(
            elaborateRatingEnabled = false,
            ratingConfig = config,
            ratingProfiles = profiles(mediaType, config.categories),
            mergeEnabled = false,
            oneCoverPerGroup = mediaType == MediaType.SERIES
        )
    }

    fun allMediaSettings(): Map<MediaType, MediaSettings> =
        MediaType.entries.associateWith(::mediaSettings)

    fun canonicalCategory(mediaType: MediaType, categoryId: String): RatingCategory? =
        categoriesByMedia[mediaType]?.firstOrNull { it.id == categoryId }?.copy()

    fun canonicalProfile(mediaType: MediaType, profileId: String): RatingProfile? =
        profiles(mediaType).profiles.firstOrNull { it.id == profileId }

    // Anime compatibility API retained while existing Anime UI/repository code is adapted.
    val defaultRatingCategories = animeCategories.map(RatingCategory::copy)
    val defaultRatingConfig = RatingSystemConfig(defaultRatingCategories, defaultAdditiveBonus)

    fun defaultProfiles(categories: List<RatingCategory> = defaultRatingCategories): RatingProfilesConfig {
        return profiles(MediaType.ANIME, categories)
    }

    fun initialProfilesForExistingCategories(categories: List<RatingCategory>): RatingProfilesConfig {
        val defaults = defaultProfiles(categories)
        return defaults.copy(
            profiles = defaults.profiles.map { profile ->
                if (profile.id == GENERAL_PROFILE_ID) {
                    profile.copy(weights = categories.associate { it.id to it.weight }.ensureAtLeastOneWeight())
                } else {
                    profile
                }
            }
        )
    }
}

data class AppSettingsState(
    val showImages: Boolean = false,
    val stickyScore: Boolean = true,
    val activeMediaType: MediaType = MediaType.ANIME,
    val catalogSortOrders: Map<MediaType, CatalogSortOrder> = defaultCatalogSortOrders(),
    val mediaSettings: Map<MediaType, MediaSettings> = DefaultSettings.allMediaSettings(),
    val appIcon: AppIcon = AppIcon.DARK,
    val themeSelection: ThemeSelection = ThemeSelection.BuiltIn(AppThemePalette.MIDNIGHT_NEON),
    val customPalettes: List<CustomAppPalette> = emptyList(),
    val builtInPaletteOverrides: Map<AppThemePalette, CustomAppPalette> = emptyMap(),
    val hiddenBuiltInPalettes: Set<AppThemePalette> = emptySet(),
    val language: SupportedLanguage = SupportedLanguage.DEFAULT,
    val hasCompletedWelcome: Boolean = false,
    val hasCompletedOnboarding: Boolean = false,
    val completedTopCoverWarmups: Set<MediaType> = emptySet(),
    val settingsLoaded: Boolean = false
) {
    val themePalette: AppThemePalette
        get() = (themeSelection as? ThemeSelection.BuiltIn)?.palette
            ?: AppThemePalette.MIDNIGHT_NEON
    val activePalette: AppPalette
        get() = themeSelection.resolve(customPalettes, builtInPaletteOverrides)
    val activeMediaSettings: MediaSettings
        get() = mediaSettings[activeMediaType] ?: DefaultSettings.mediaSettings(activeMediaType)

    val elaborateRatingEnabled: Boolean get() = activeMediaSettings.elaborateRatingEnabled
    val ratingConfig: RatingSystemConfig get() = activeMediaSettings.ratingConfig
    val ratingProfiles: RatingProfilesConfig get() = activeMediaSettings.ratingProfiles
    val mergeEnabled: Boolean get() = activeMediaSettings.mergeEnabled
    val oneCoverPerGroup: Boolean get() = activeMediaSettings.oneCoverPerGroup

    /** Temporary bridge for the existing Anime Search/Ranking call sites. */
    val mergeSeasonsEnabled: Boolean get() = mergeEnabled
}

class SettingsRepository internal constructor(
    private val context: Context,
    private val iconAliasController: AppIconAliasController = PackageManagerAppIconAliasController(context),
    private val localeController: AppLocaleController = AndroidXAppLocaleController()
) {
    private val json = settingsJson
    private val updateMutex = Mutex()

    private val stickyScoreKey = booleanPreferencesKey("sticky_score")
    private val showImagesKey = booleanPreferencesKey("show_images")
    private val activeMediaTypeKey = stringPreferencesKey("active_media_type")
    private val catalogSortOrdersKey = stringSetPreferencesKey("catalog_sort_orders_v1")
    private val elaborateRatingEnabledKey = booleanPreferencesKey("elaborate_rating_enabled")
    private val ratingConfigKey = stringPreferencesKey("rating_config")
    private val ratingProfilesKey = stringPreferencesKey("rating_profiles")
    private val mediaSettingsKey = stringPreferencesKey("media_settings_v1")
    private val appIconKey = stringPreferencesKey("app_icon")
    private val themePaletteKey = stringPreferencesKey("theme_palette")
    private val customPalettesKey = stringPreferencesKey("custom_app_palettes_v1")
    private val languageKey = stringPreferencesKey("language_tag")
    private val mergeSeasonsEnabledKey = booleanPreferencesKey("merge_seasons_enabled")
    private val hasCompletedWelcomeKey = booleanPreferencesKey("has_completed_welcome")
    private val hasCompletedOnboardingKey = booleanPreferencesKey("has_completed_onboarding")
    private val completedOnboardingVersionKey = intPreferencesKey("completed_onboarding_version")
    private val completedTopCoverWarmupsKey = stringSetPreferencesKey("completed_top_cover_warmups_v1")

    private val _state = MutableStateFlow(AppSettingsState(settingsLoaded = false))
    val state: StateFlow<AppSettingsState> = _state.asStateFlow()
    private var removedLegacyCategoryIds: List<String> = emptyList()

    suspend fun loadInitialState() {
        val prefs = context.dataStore.data.first()
        val onboarding = resolveOnboardingState(
            completedVersion = prefs[completedOnboardingVersionKey]
        )
        val legacyInput = LegacyAnimeSettings(
            elaborateRatingEnabled = prefs[elaborateRatingEnabledKey],
            ratingConfig = decodeOrNull(prefs[ratingConfigKey]),
            ratingProfiles = decodeOrNull(prefs[ratingProfilesKey]),
            mergeEnabled = prefs[mergeSeasonsEnabledKey]
        )
        val legacyMigration = migrateLegacyAnimeSettings(legacyInput)
        val persistedSnapshot = decodeMediaSettingsSnapshot(prefs[mediaSettingsKey])
        val mediaSettings = normalizeMediaSettingsSnapshot(persistedSnapshot, legacyMigration.settings)
        removedLegacyCategoryIds = if (
            persistedSnapshot?.media?.containsKey(MediaType.ANIME.persistedValue) != true
        ) {
            legacyMigration.removedCategoryIds
        } else {
            emptyList()
        }

        val persistedAppIcon = prefs[appIconKey]
        val requestedAppIcon = AppIcon.fromPersistedValue(persistedAppIcon)
        val actualAppIcon = iconAliasController.activeIconOrNull()
        val appIcon = try {
            if (actualAppIcon != requestedAppIcon) {
                iconAliasController.activate(requestedAppIcon, actualAppIcon ?: AppIcon.DARK)
            }
            requestedAppIcon
        } catch (exception: Exception) {
            android.util.Log.e("RankOfflineIcon", "Unable to reconcile launcher alias", exception)
            actualAppIcon ?: AppIcon.DARK
        }
        val themeWorkspace = decodeThemeWorkspace(prefs[customPalettesKey])
        val customPalettes = themeWorkspace.customPalettes
        val parsedThemeSelection = ThemeSelection.fromPersistedValue(
            prefs[themePaletteKey],
            customPalettes
        )
        val themeSelection = if (
            parsedThemeSelection is ThemeSelection.BuiltIn &&
            parsedThemeSelection.palette in themeWorkspace.hiddenBuiltIns
        ) {
            AppThemePalette.entries.firstOrNull { it !in themeWorkspace.hiddenBuiltIns }
                ?.let { ThemeSelection.BuiltIn(it) }
                ?: customPalettes.firstOrNull()?.let { ThemeSelection.Custom(it.id) }
                ?: ThemeSelection.BuiltIn(AppThemePalette.MIDNIGHT_NEON)
        } else {
            parsedThemeSelection
        }
        val language = SupportedLanguage.fromLanguageTag(prefs[languageKey])
        if (persistedAppIcon != appIcon.persistedValue) {
            context.dataStore.edit { mutablePrefs ->
                mutablePrefs[appIconKey] = appIcon.persistedValue
            }
        }
        val normalizedSnapshot = encodeMediaSettingsSnapshot(mediaSettings)
        val animeMirror = mediaSettings.getValue(MediaType.ANIME).toLegacyAnimeSettings()
        context.dataStore.edit { mutablePrefs ->
            mutablePrefs[mediaSettingsKey] = normalizedSnapshot
            mutablePrefs[themePaletteKey] = themeSelection.persistedValue
            mirrorLegacyAnimeSettings(mutablePrefs, animeMirror)
        }
        _state.value = AppSettingsState(
            showImages = resolveShowImages(prefs[showImagesKey]),
            stickyScore = prefs[stickyScoreKey] ?: true,
            activeMediaType = resolveActiveMediaType(prefs[activeMediaTypeKey]),
            catalogSortOrders = resolveCatalogSortOrders(prefs[catalogSortOrdersKey]),
            mediaSettings = mediaSettings,
            appIcon = appIcon,
            themeSelection = themeSelection,
            customPalettes = customPalettes,
            builtInPaletteOverrides = themeWorkspace.builtInOverrides,
            hiddenBuiltInPalettes = themeWorkspace.hiddenBuiltIns,
            language = language,
            hasCompletedWelcome = if (BuildConfig.DEBUG) false else onboarding.hasCompletedWelcome,
            hasCompletedOnboarding = if (BuildConfig.DEBUG) true else onboarding.hasCompletedOnboarding,
            completedTopCoverWarmups = prefs[completedTopCoverWarmupsKey].orEmpty()
                .map(MediaType::fromPersistedValue)
                .toSet(),
            settingsLoaded = true
        )
        localeController.apply(language)
    }

    internal fun takeRemovedLegacyCategoryIds(): List<String> =
        removedLegacyCategoryIds.also { removedLegacyCategoryIds = emptyList() }

    suspend fun updateShowImages(value: Boolean) = updateMutex.withLock {
        _state.value = _state.value.copy(showImages = value)
        context.dataStore.edit { prefs -> prefs[showImagesKey] = value }
    }

    suspend fun updateStickyScore(value: Boolean) = updateMutex.withLock {
        _state.value = _state.value.copy(stickyScore = value)
        context.dataStore.edit { prefs -> prefs[stickyScoreKey] = value }
    }

    suspend fun updateActiveMediaType(value: MediaType) = updateMutex.withLock {
        context.dataStore.edit { prefs -> prefs[activeMediaTypeKey] = value.toPackId().value }
        _state.value = _state.value.withActiveMediaType(value)
    }

    suspend fun updateCatalogSortOrder(
        mediaType: MediaType,
        value: CatalogSortOrder
    ) = updateMutex.withLock {
        val updated = _state.value.catalogSortOrders + (mediaType to value)
        context.dataStore.edit { prefs ->
            prefs[catalogSortOrdersKey] = encodeCatalogSortOrders(updated)
        }
        _state.value = _state.value.copy(catalogSortOrders = updated)
    }

    suspend fun updateElaborateRatingEnabled(value: Boolean) = updateMutex.withLock {
        persistActiveMediaSettings { it.copy(elaborateRatingEnabled = value) }
    }

    suspend fun updateRatingConfig(config: RatingSystemConfig) = updateMutex.withLock {
        val mediaType = _state.value.activeMediaType
        val profiles = reconcileProfiles(_state.value.ratingProfiles, config.categories, mediaType)
        persistActiveMediaSettings { it.copy(ratingConfig = config, ratingProfiles = profiles) }
    }

    suspend fun updateRatingProfiles(config: RatingProfilesConfig) = updateMutex.withLock {
        val mediaType = _state.value.activeMediaType
        val profiles = reconcileProfiles(config, _state.value.ratingConfig.categories, mediaType)
        persistActiveMediaSettings { it.copy(ratingProfiles = profiles) }
    }

    suspend fun updateRatingSetup(config: RatingSystemConfig, profiles: RatingProfilesConfig) = updateMutex.withLock {
        val mediaType = _state.value.activeMediaType
        persistActiveMediaSettings {
            it.copy(ratingConfig = config, ratingProfiles = reconcileProfiles(profiles, config.categories, mediaType))
        }
    }

    suspend fun updateAppIcon(value: AppIcon) = updateMutex.withLock {
        val previousIcon = _state.value.appIcon
        persistAndActivateAppIcon(
            icon = value,
            previousIcon = previousIcon,
            persistSelection = { icon ->
                context.dataStore.edit { prefs -> prefs[appIconKey] = icon.persistedValue }
                _state.value = _state.value.copy(appIcon = icon)
            },
            activateAlias = ::ensureSelectedIcon
        )
    }

    suspend fun updateThemePalette(value: AppThemePalette) = updateMutex.withLock {
        persistThemeSelection(ThemeSelection.BuiltIn(value))
    }

    suspend fun updateThemeSelection(value: ThemeSelection) = updateMutex.withLock {
        val safeSelection = when (value) {
            is ThemeSelection.BuiltIn -> value.takeIf {
                it.palette !in _state.value.hiddenBuiltInPalettes
            } ?: ThemeSelection.BuiltIn(AppThemePalette.MIDNIGHT_NEON)
            is ThemeSelection.Custom -> value.takeIf { selected ->
                _state.value.customPalettes.any { it.id == selected.id }
            } ?: ThemeSelection.BuiltIn(AppThemePalette.MIDNIGHT_NEON)
        }
        persistThemeSelection(safeSelection)
    }

    suspend fun saveCustomTheme(
        value: CustomAppPalette,
        selectAfterSave: Boolean
    ) = updateMutex.withLock {
        val updated = _state.value.withCustomPaletteSaved(value, selectAfterSave)
        context.dataStore.edit { prefs ->
            prefs[customPalettesKey] = encodeThemeWorkspace(updated.themeWorkspace())
            prefs[themePaletteKey] = updated.themeSelection.persistedValue
        }
        _state.value = updated
    }

    suspend fun deleteCustomTheme(id: String) = updateMutex.withLock {
        val updated = _state.value.withCustomPaletteDeleted(id)
        context.dataStore.edit { prefs ->
            prefs[customPalettesKey] = encodeThemeWorkspace(updated.themeWorkspace())
            prefs[themePaletteKey] = updated.themeSelection.persistedValue
        }
        _state.value = updated
    }

    suspend fun saveBuiltInTheme(
        builtIn: AppThemePalette,
        value: CustomAppPalette
    ) = updateMutex.withLock {
        val updated = _state.value.withBuiltInPaletteSaved(builtIn, value)
        persistThemeWorkspace(updated)
    }

    suspend fun deleteBuiltInTheme(builtIn: AppThemePalette) = updateMutex.withLock {
        val updated = _state.value.withBuiltInPaletteDeleted(builtIn)
        persistThemeWorkspace(updated)
    }

    suspend fun resetBuiltInThemes() = updateMutex.withLock {
        val updated = _state.value.withBuiltInPalettesReset()
        persistThemeWorkspace(updated)
    }

    suspend fun updateLanguage(value: SupportedLanguage) = updateMutex.withLock {
        context.dataStore.edit { prefs -> prefs[languageKey] = value.languageTag }
        _state.value = _state.value.copy(language = value)
        localeController.apply(value)
    }

    suspend fun updateMergeEnabled(value: Boolean) = updateMutex.withLock {
        persistActiveMediaSettings { it.copy(mergeEnabled = value) }
    }

    suspend fun updateOneCoverPerGroup(value: Boolean) = updateMutex.withLock {
        persistActiveMediaSettings { it.copy(oneCoverPerGroup = value) }
    }

    /** Apply a pack's suggested cover behavior only on its first successful installation. */
    suspend fun applyNewPackCoverDefault(mediaType: MediaType, value: Boolean) = updateMutex.withLock {
        val updated = updateMediaSettings(_state.value.mediaSettings, mediaType) {
            it.copy(oneCoverPerGroup = value)
        }
        context.dataStore.edit { prefs ->
            prefs[mediaSettingsKey] = encodeMediaSettingsSnapshot(updated)
        }
        _state.value = _state.value.copy(mediaSettings = updated, settingsLoaded = true)
    }

    suspend fun completeWelcome(showImages: Boolean, elaborateRating: Boolean) = updateMutex.withLock {
        val updatedMediaSettings = applyOnboardingRatingStyle(
            _state.value.mediaSettings,
            elaborateRating
        )
        context.dataStore.edit { prefs ->
            prefs[showImagesKey] = showImages
            prefs[mediaSettingsKey] = encodeMediaSettingsSnapshot(updatedMediaSettings)
            mirrorLegacyAnimeSettings(
                prefs,
                updatedMediaSettings.getValue(MediaType.ANIME).toLegacyAnimeSettings()
            )
            prefs[hasCompletedWelcomeKey] = true
        }
        _state.value = _state.value.copy(
            showImages = showImages,
            mediaSettings = updatedMediaSettings,
            hasCompletedWelcome = true,
            settingsLoaded = true
        )
    }

    suspend fun completeOnboarding() = updateMutex.withLock {
        context.dataStore.edit { prefs ->
            prefs[hasCompletedWelcomeKey] = true
            prefs[hasCompletedOnboardingKey] = true
            prefs[completedOnboardingVersionKey] = CURRENT_ONBOARDING_VERSION
        }
        _state.value = _state.value.copy(
            hasCompletedWelcome = true,
            hasCompletedOnboarding = true,
            settingsLoaded = true
        )
    }

    suspend fun markTopCoverWarmupCompleted(mediaType: MediaType) = updateMutex.withLock {
        val updated = _state.value.completedTopCoverWarmups + mediaType
        context.dataStore.edit { prefs ->
            prefs[completedTopCoverWarmupsKey] = updated.mapTo(mutableSetOf()) { it.persistedValue }
        }
        _state.value = _state.value.copy(completedTopCoverWarmups = updated)
    }

    suspend fun clearTopCoverWarmup(mediaType: MediaType) = updateMutex.withLock {
        val updated = _state.value.completedTopCoverWarmups - mediaType
        context.dataStore.edit { prefs ->
            prefs[completedTopCoverWarmupsKey] = updated.mapTo(mutableSetOf()) { it.persistedValue }
        }
        _state.value = _state.value.copy(completedTopCoverWarmups = updated)
    }

    // Compatibility bridge until the existing Anime call sites use the generic name.
    suspend fun updateMergeSeasonsEnabled(value: Boolean) = updateMergeEnabled(value)

    suspend fun resetRatingCategories() = updateMutex.withLock {
        val mediaType = _state.value.activeMediaType
        persistActiveMediaSettings {
            it.withDefaultCategories(mediaType)
        }
    }

    suspend fun resetRatingProfiles() = updateMutex.withLock {
        val mediaType = _state.value.activeMediaType
        persistActiveMediaSettings {
            it.withDefaultProfiles(mediaType)
        }
    }

    /** Used only by the explicit destructive "delete my data" category action. */
    suspend fun resetMediaSettings(mediaType: MediaType) = updateMutex.withLock {
        val updated = resetMediaSettingsForCategory(_state.value.mediaSettings, mediaType)
        context.dataStore.edit { prefs ->
            prefs[mediaSettingsKey] = encodeMediaSettingsSnapshot(updated)
            if (mediaType == MediaType.ANIME) {
                mirrorLegacyAnimeSettings(prefs, updated.getValue(MediaType.ANIME).toLegacyAnimeSettings())
            }
        }
        _state.value = _state.value.copy(mediaSettings = updated, settingsLoaded = true)
    }

    suspend fun purgePackSettings(mediaType: MediaType) = updateMutex.withLock {
        val updatedSettings = resetMediaSettingsForCategory(_state.value.mediaSettings, mediaType)
        val updatedSortOrders = _state.value.catalogSortOrders + (mediaType to CatalogSortOrder.POPULARITY)
        context.dataStore.edit { prefs ->
            prefs[mediaSettingsKey] = encodeMediaSettingsSnapshot(updatedSettings)
            prefs[catalogSortOrdersKey] = encodeCatalogSortOrders(updatedSortOrders)
            if (mediaType == MediaType.ANIME) {
                mirrorLegacyAnimeSettings(prefs, updatedSettings.getValue(MediaType.ANIME).toLegacyAnimeSettings())
            }
        }
        _state.value = _state.value.copy(
            mediaSettings = updatedSettings,
            catalogSortOrders = updatedSortOrders
        )
    }

    private suspend fun persistActiveMediaSettings(transform: (MediaSettings) -> MediaSettings) {
        val mediaType = _state.value.activeMediaType
        val updated = updateMediaSettings(_state.value.mediaSettings, mediaType, transform)
        context.dataStore.edit { prefs ->
            prefs[mediaSettingsKey] = encodeMediaSettingsSnapshot(updated)
            if (mediaType == MediaType.ANIME) {
                mirrorLegacyAnimeSettings(prefs, updated.getValue(MediaType.ANIME).toLegacyAnimeSettings())
            }
        }
        _state.value = _state.value.copy(mediaSettings = updated, settingsLoaded = true)
    }

    private suspend fun persistThemeSelection(selection: ThemeSelection) {
        context.dataStore.edit { prefs -> prefs[themePaletteKey] = selection.persistedValue }
        _state.value = _state.value.copy(themeSelection = selection)
    }

    private suspend fun persistThemeWorkspace(state: AppSettingsState) {
        context.dataStore.edit { prefs ->
            prefs[customPalettesKey] = encodeThemeWorkspace(state.themeWorkspace())
            prefs[themePaletteKey] = state.themeSelection.persistedValue
        }
        _state.value = state
    }

    private inline fun <reified T> decodeOrNull(value: String?): T? {
        if (value == null) return null
        return try { json.decodeFromString<T>(value) } catch (_: Exception) { null }
    }

    private fun mirrorLegacyAnimeSettings(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        mirror: LegacyAnimeSettings
    ) {
        prefs[elaborateRatingEnabledKey] = mirror.elaborateRatingEnabled ?: false
        prefs[ratingConfigKey] = json.encodeToString(requireNotNull(mirror.ratingConfig))
        prefs[ratingProfilesKey] = json.encodeToString(requireNotNull(mirror.ratingProfiles))
        prefs[mergeSeasonsEnabledKey] = mirror.mergeEnabled ?: false
    }

    private fun ensureSelectedIcon(icon: AppIcon, previousIcon: AppIcon) {
        // Propagate activation failures. Do not persist an icon that wasn't applied.
        iconAliasController.activate(icon, previousIcon)
    }
}

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

internal fun resetMediaSettingsForCategory(
    settings: Map<MediaType, MediaSettings>,
    mediaType: MediaType
): Map<MediaType, MediaSettings> =
    settings + (mediaType to DefaultSettings.mediaSettings(mediaType))

internal data class OnboardingPersistenceState(
    val hasCompletedWelcome: Boolean,
    val hasCompletedOnboarding: Boolean
)

internal fun applyOnboardingRatingStyle(
    mediaSettings: Map<MediaType, MediaSettings>,
    elaborateRating: Boolean
): Map<MediaType, MediaSettings> = mediaSettings.mapValues { (_, settings) ->
    settings.copy(elaborateRatingEnabled = elaborateRating)
}

/** Versioned independently from the app version so generation-two onboarding also runs once on upgrades. */
internal fun resolveOnboardingState(
    completedVersion: Int?
): OnboardingPersistenceState {
    val completed = (completedVersion ?: 0) >= CURRENT_ONBOARDING_VERSION
    return OnboardingPersistenceState(
        hasCompletedWelcome = completed,
        hasCompletedOnboarding = completed
    )
}

internal const val CURRENT_ONBOARDING_VERSION = 2

internal fun reconcileProfiles(
    config: RatingProfilesConfig,
    categories: List<RatingCategory>,
    mediaType: MediaType = MediaType.ANIME
): RatingProfilesConfig {
    val categoryIds = categories.map { it.id }
    val uniqueProfiles = config.profiles.distinctBy { it.id }.filter { it.id.isNotBlank() }
    val source = uniqueProfiles.ifEmpty { DefaultSettings.profiles(mediaType, categories).profiles }
    return RatingProfilesConfig(
        source.map { profile ->
            profile.copy(
                name = profile.name.ifBlank { "Profile" },
                weights = categoryIds.associateWith { id ->
                    profile.weights[id]?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0
                }.ensureAtLeastOneWeight()
            )
        }
    )
}

internal fun RatingProfilesConfig.withCategoryAdded(categoryId: String): RatingProfilesConfig = copy(
    profiles = profiles.map { it.copy(weights = it.weights + (categoryId to 0.0)) }
)

internal fun RatingProfilesConfig.withCategoryRemoved(categoryId: String): RatingProfilesConfig = copy(
    profiles = profiles.map { it.copy(weights = it.weights - categoryId) }
)

internal fun RatingProfilesConfig.withProfileDeleted(profileId: String): RatingProfilesConfig =
    if (profiles.size <= 1) this else copy(profiles = profiles.filterNot { it.id == profileId })

internal fun RatingProfilesConfig.resolveProfile(profileId: String?): RatingProfile =
    profiles.firstOrNull { it.id == profileId }
        ?: profiles.firstOrNull { it.id == DefaultSettings.GENERAL_PROFILE_ID }
        ?: profiles.first()

internal fun RatingSystemConfig.withProfile(profile: RatingProfile): RatingSystemConfig = copy(
    categories = categories.map { category -> category.copy(weight = profile.weights[category.id] ?: 0.0) }
)

private fun Map<String, Double>.ensureAtLeastOneWeight(): Map<String, Double> =
    if (isNotEmpty() && values.none { it.isFinite() && it > 0.0 }) mapValues { 5.0 } else this

internal fun resolveShowImages(persisted: Boolean?): Boolean = persisted ?: false

internal fun resolveActiveMediaType(persisted: String?): MediaType =
    OfficialPackIds.all.firstOrNull { it.value == persisted }
        ?.toOfficialMediaTypeOrNull()
        ?: MediaType.fromPersistedValue(persisted)

internal fun defaultCatalogSortOrders(): Map<MediaType, CatalogSortOrder> =
    MediaType.entries.associateWith { CatalogSortOrder.POPULARITY }

internal fun resolveCatalogSortOrders(
    persisted: Set<String>?
): Map<MediaType, CatalogSortOrder> {
    val resolved = defaultCatalogSortOrders().toMutableMap()
    persisted.orEmpty().forEach { entry ->
        val parts = entry.split('=', limit = 2)
        val persistedMedia = parts.getOrNull(0)
        val mediaType = OfficialPackIds.all.firstOrNull { it.value == persistedMedia }
            ?.toOfficialMediaTypeOrNull()
            ?: MediaType.entries.firstOrNull { it.persistedValue == persistedMedia }
            ?: return@forEach
        val sortOrder = parts.getOrNull(1)?.let { value ->
            CatalogSortOrder.entries.firstOrNull { it.name == value }
        } ?: return@forEach
        resolved[mediaType] = sortOrder
    }
    return resolved
}

internal fun encodeCatalogSortOrders(
    sortOrders: Map<MediaType, CatalogSortOrder>
): Set<String> = MediaType.entries.mapTo(linkedSetOf()) { mediaType ->
    "${mediaType.toPackId().value}=${sortOrders[mediaType] ?: CatalogSortOrder.POPULARITY}"
}

internal fun AppSettingsState.withActiveMediaType(value: MediaType): AppSettingsState =
    copy(activeMediaType = value)

internal fun resolveElaborateRatingEnabled(persisted: Boolean?): Boolean = persisted ?: false

internal fun resolveMergeSeasonsEnabled(persisted: Boolean?): Boolean = persisted ?: false

internal fun resolveRatingConfig(persisted: RatingSystemConfig?): RatingSystemConfig =
    persisted ?: DefaultSettings.defaultRatingConfig

internal data class RatingConfigNormalization(
    val config: RatingSystemConfig,
    val removedCategoryIds: List<String>
)

internal fun normalizeLegacyInactiveCategories(config: RatingSystemConfig): RatingConfigNormalization {
    val removedIds = config.categories.filterNot { it.active }.map { it.id }
    return RatingConfigNormalization(
        config = if (removedIds.isEmpty()) config else config.copy(categories = config.categories.filter { it.active }),
        removedCategoryIds = removedIds
    )
}

internal data class LegacyAnimeSettings(
    val elaborateRatingEnabled: Boolean? = null,
    val ratingConfig: RatingSystemConfig? = null,
    val ratingProfiles: RatingProfilesConfig? = null,
    val mergeEnabled: Boolean? = null
)

internal data class LegacyAnimeMigration(
    val settings: MediaSettings,
    val removedCategoryIds: List<String>
)

internal fun migrateLegacyAnimeSettings(legacy: LegacyAnimeSettings): LegacyAnimeMigration {
    val normalization = normalizeLegacyInactiveCategories(resolveRatingConfig(legacy.ratingConfig))
    val initialProfiles = legacy.ratingProfiles
        ?: if (legacy.ratingConfig == null) {
            DefaultSettings.defaultProfiles(normalization.config.categories)
        } else {
            DefaultSettings.initialProfilesForExistingCategories(normalization.config.categories)
        }
    return LegacyAnimeMigration(
        settings = MediaSettings(
            elaborateRatingEnabled = resolveElaborateRatingEnabled(legacy.elaborateRatingEnabled),
            ratingConfig = normalization.config,
            ratingProfiles = reconcileProfiles(
                initialProfiles,
                normalization.config.categories,
                MediaType.ANIME
            ),
            mergeEnabled = resolveMergeSeasonsEnabled(legacy.mergeEnabled)
        ),
        removedCategoryIds = normalization.removedCategoryIds
    )
}

internal fun MediaSettings.toLegacyAnimeSettings(): LegacyAnimeSettings = LegacyAnimeSettings(
    elaborateRatingEnabled = elaborateRatingEnabled,
    ratingConfig = ratingConfig,
    ratingProfiles = ratingProfiles,
    mergeEnabled = mergeEnabled
)

internal fun decodeMediaSettingsSnapshot(value: String?): PersistedMediaSettings? {
    if (value == null) return null
    return try {
        val mediaObject = settingsJson.parseToJsonElement(value)
            .jsonObject["media"]
            ?.jsonObject
            ?: return null
        PersistedMediaSettings(
            media = mediaObject.mapNotNull { (key, element) ->
                runCatching { settingsJson.decodeFromJsonElement<MediaSettings>(element) }
                    .getOrNull()
                    ?.let { key to it }
            }.toMap()
        )
    } catch (_: Exception) {
        null
    }
}

internal fun encodeMediaSettingsSnapshot(settings: Map<MediaType, MediaSettings>): String =
    settingsJson.encodeToString(
        PersistedMediaSettings(
            media = settings.entries.associate { (mediaType, value) ->
                mediaType.toPackId().value to value
            }
        )
    )

internal fun normalizeMediaSettingsSnapshot(
    snapshot: PersistedMediaSettings?,
    legacyAnimeSettings: MediaSettings
): Map<MediaType, MediaSettings> = MediaType.entries.associateWith { mediaType ->
    val fallback = if (mediaType == MediaType.ANIME) {
        legacyAnimeSettings
    } else {
        DefaultSettings.mediaSettings(mediaType)
    }
    normalizeMediaSettings(
        mediaType,
        snapshot?.media?.get(mediaType.toPackId().value)
            ?: snapshot?.media?.get(mediaType.persistedValue)
            ?: fallback
    )
}

private fun normalizeMediaSettings(mediaType: MediaType, settings: MediaSettings): MediaSettings {
    val categories = settings.ratingConfig.categories
        .filter { it.id.isNotBlank() }
        .distinctBy { it.id }
    val config = settings.ratingConfig.copy(categories = categories)
    return settings.copy(
        ratingConfig = config,
        ratingProfiles = reconcileProfiles(settings.ratingProfiles, categories, mediaType)
    )
}

internal fun updateMediaSettings(
    settings: Map<MediaType, MediaSettings>,
    mediaType: MediaType,
    transform: (MediaSettings) -> MediaSettings
): Map<MediaType, MediaSettings> {
    val current = settings[mediaType] ?: DefaultSettings.mediaSettings(mediaType)
    return settings + (mediaType to normalizeMediaSettings(mediaType, transform(current)))
}

internal fun MediaSettings.withDefaultCategories(mediaType: MediaType): MediaSettings {
    val config = ratingConfig.copy(categories = DefaultSettings.ratingConfig(mediaType).categories)
    return copy(
        ratingConfig = config,
        ratingProfiles = reconcileProfiles(ratingProfiles, config.categories, mediaType)
    )
}

internal fun MediaSettings.withDefaultProfiles(mediaType: MediaType): MediaSettings = copy(
    ratingProfiles = DefaultSettings.profiles(mediaType, ratingConfig.categories)
)
