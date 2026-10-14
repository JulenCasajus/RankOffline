package org.rankoffline.app

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "app_preferences")

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

object DefaultSettings {
    const val GENERAL_PROFILE_ID = "general"

    val defaultRatingCategories = listOf(
        RatingCategory("writing", "Writing", "Plot, themes, pacing, highlights, beginning, ending", 35.0, "#19D5E5", 0),
        RatingCategory("characters", "Characters", "Main characters, supporting characters, antagonist, development, depth, charisma, dynamics", 20.0, "#E04BCF", 1),
        RatingCategory("visuals", "Visuals", "Animation, art style, cinematography, backgrounds, effects, character design", 20.0, "#FFAA55", 2),
        RatingCategory("worldbuilding", "Worldbuilding", "Setting, lore, power system", 15.0, "#62D6C8", 3),
        RatingCategory("audio", "Audio", "OST, openings/endings, sound effects", 10.0, "#7EC7FF", 4)
    )

    val defaultAdditiveBonus = AdditiveBonus("Personal taste", "How much you liked it personally, independent of technical quality", 1.0, "#E04BCF")
    val defaultRatingConfig = RatingSystemConfig(
        categories = defaultRatingCategories,
        additiveBonus = defaultAdditiveBonus
    )

    private val defaultProfileWeights = linkedMapOf(
        GENERAL_PROFILE_ID to listOf(35.0, 20.0, 20.0, 15.0, 10.0),
        "narrative" to listOf(45.0, 25.0, 10.0, 10.0, 10.0),
        "character_driven" to listOf(25.0, 40.0, 15.0, 10.0, 10.0),
        "visual_action" to listOf(20.0, 15.0, 40.0, 15.0, 10.0),
        "world_adventure" to listOf(20.0, 20.0, 15.0, 35.0, 10.0),
        "atmospheric" to listOf(25.0, 20.0, 20.0, 15.0, 20.0)
    )

    private val defaultProfileNames = mapOf(
        GENERAL_PROFILE_ID to "General",
        "narrative" to "Narrative",
        "character_driven" to "Character-driven",
        "visual_action" to "Visual / Action",
        "world_adventure" to "World / Adventure",
        "atmospheric" to "Atmospheric"
    )

    fun defaultProfiles(categories: List<RatingCategory> = defaultRatingCategories): RatingProfilesConfig {
        val availableIds = categories.map { it.id }
        val defaultIds = defaultRatingCategories.map { it.id }
        return RatingProfilesConfig(
            defaultProfileWeights.map { (profileId, presetWeights) ->
                val weights = availableIds.associateWith { categoryId ->
                    defaultIds.indexOf(categoryId).takeIf { it >= 0 }?.let(presetWeights::get) ?: 0.0
                }.ensureAtLeastOneWeight()
                RatingProfile(profileId, defaultProfileNames.getValue(profileId), weights)
            }
        )
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
    val elaborateRatingEnabled: Boolean = false,
    val ratingConfig: RatingSystemConfig = DefaultSettings.defaultRatingConfig,
    val ratingProfiles: RatingProfilesConfig = DefaultSettings.defaultProfiles(),
    val appIcon: AppIcon = AppIcon.DARK,
    val language: SupportedLanguage = SupportedLanguage.DEFAULT,
    val mergeSeasonsEnabled: Boolean = false,
    val settingsLoaded: Boolean = false
)

class SettingsRepository internal constructor(
    private val context: Context,
    private val iconAliasController: AppIconAliasController = PackageManagerAppIconAliasController(context),
    private val localeController: AppLocaleController = AndroidXAppLocaleController()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val updateMutex = Mutex()

    private val stickyScoreKey = booleanPreferencesKey("sticky_score")
    private val showImagesKey = booleanPreferencesKey("show_images")
    private val elaborateRatingEnabledKey = booleanPreferencesKey("elaborate_rating_enabled")
    private val ratingConfigKey = stringPreferencesKey("rating_config")
    private val ratingProfilesKey = stringPreferencesKey("rating_profiles")
    private val appIconKey = stringPreferencesKey("app_icon")
    private val languageKey = stringPreferencesKey("language_tag")
    private val mergeSeasonsEnabledKey = booleanPreferencesKey("merge_seasons_enabled")

    private val _state = MutableStateFlow(AppSettingsState(settingsLoaded = false))
    val state: StateFlow<AppSettingsState> = _state.asStateFlow()
    private var removedLegacyCategoryIds: List<String> = emptyList()

    suspend fun loadInitialState() {
        val prefs = context.dataStore.data.first()
        val ratingJson = prefs[ratingConfigKey]
        val persistedRatingConfig = ratingJson?.let {
            try { json.decodeFromString<RatingSystemConfig>(it) } catch (_: Exception) { null }
        }
        val normalization = normalizeLegacyInactiveCategories(resolveRatingConfig(persistedRatingConfig))
        val ratingConfig = normalization.config
        removedLegacyCategoryIds = normalization.removedCategoryIds
        val persistedProfiles = prefs[ratingProfilesKey]?.let {
            try { json.decodeFromString<RatingProfilesConfig>(it) } catch (_: Exception) { null }
        }
        val initialProfiles = persistedProfiles
            ?: if (persistedRatingConfig == null) DefaultSettings.defaultProfiles(ratingConfig.categories)
            else DefaultSettings.initialProfilesForExistingCategories(ratingConfig.categories)
        val ratingProfiles = reconcileProfiles(initialProfiles, ratingConfig.categories)
        if (normalization.removedCategoryIds.isNotEmpty() || persistedProfiles != ratingProfiles) {
            context.dataStore.edit { mutablePrefs ->
                mutablePrefs[ratingConfigKey] = json.encodeToString(ratingConfig)
                mutablePrefs[ratingProfilesKey] = json.encodeToString(ratingProfiles)
            }
        }

        val persistedAppIcon = prefs[appIconKey]
        val appIcon = AppIcon.fromPersistedValue(persistedAppIcon)
        val language = SupportedLanguage.fromLanguageTag(prefs[languageKey])
        if (persistedAppIcon != null && persistedAppIcon != appIcon.persistedValue) {
            context.dataStore.edit { mutablePrefs ->
                mutablePrefs[appIconKey] = AppIcon.DARK.persistedValue
            }
        }
        _state.value = AppSettingsState(
            showImages = resolveShowImages(prefs[showImagesKey]),
            stickyScore = prefs[stickyScoreKey] ?: true,
            elaborateRatingEnabled = resolveElaborateRatingEnabled(prefs[elaborateRatingEnabledKey]),
            ratingConfig = ratingConfig,
            ratingProfiles = ratingProfiles,
            appIcon = appIcon,
            language = language,
            mergeSeasonsEnabled = resolveMergeSeasonsEnabled(prefs[mergeSeasonsEnabledKey]),
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

    suspend fun updateElaborateRatingEnabled(value: Boolean) = updateMutex.withLock {
        _state.value = _state.value.copy(elaborateRatingEnabled = value)
        context.dataStore.edit { prefs -> prefs[elaborateRatingEnabledKey] = value }
    }

    suspend fun updateRatingConfig(config: RatingSystemConfig) = updateMutex.withLock {
        val profiles = reconcileProfiles(_state.value.ratingProfiles, config.categories)
        persistRatingSetup(config, profiles)
    }

    suspend fun updateRatingProfiles(config: RatingProfilesConfig) = updateMutex.withLock {
        val profiles = reconcileProfiles(config, _state.value.ratingConfig.categories)
        persistRatingSetup(_state.value.ratingConfig, profiles)
    }

    suspend fun updateRatingSetup(config: RatingSystemConfig, profiles: RatingProfilesConfig) = updateMutex.withLock {
        persistRatingSetup(config, reconcileProfiles(profiles, config.categories))
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

    suspend fun updateLanguage(value: SupportedLanguage) = updateMutex.withLock {
        context.dataStore.edit { prefs -> prefs[languageKey] = value.languageTag }
        _state.value = _state.value.copy(language = value)
        localeController.apply(value)
    }

    suspend fun updateMergeSeasonsEnabled(value: Boolean) = updateMutex.withLock {
        context.dataStore.edit { prefs -> prefs[mergeSeasonsEnabledKey] = value }
        _state.value = _state.value.copy(mergeSeasonsEnabled = value)
    }

    suspend fun resetRatingCategories() = updateMutex.withLock {
        val config = _state.value.ratingConfig.copy(categories = DefaultSettings.defaultRatingCategories)
        persistRatingSetup(config, reconcileProfiles(_state.value.ratingProfiles, config.categories))
    }

    suspend fun resetRatingProfiles() = updateMutex.withLock {
        persistRatingSetup(
            _state.value.ratingConfig,
            DefaultSettings.defaultProfiles(_state.value.ratingConfig.categories)
        )
    }

    private suspend fun persistRatingSetup(config: RatingSystemConfig, profiles: RatingProfilesConfig) {
        _state.value = _state.value.copy(ratingConfig = config, ratingProfiles = profiles, settingsLoaded = true)
        context.dataStore.edit { prefs ->
            prefs[ratingConfigKey] = json.encodeToString(config)
            prefs[ratingProfilesKey] = json.encodeToString(profiles)
        }
    }

    private fun ensureSelectedIcon(icon: AppIcon, previousIcon: AppIcon) {
        try {
            iconAliasController.activate(icon, previousIcon)
        } catch (exception: Exception) {
            // The persisted choice remains authoritative and will be reconciled next launch.
            Log.w("SettingsRepository", "Unable to update launcher icon aliases", exception)
        }
    }
}

internal fun reconcileProfiles(
    config: RatingProfilesConfig,
    categories: List<RatingCategory>
): RatingProfilesConfig {
    val categoryIds = categories.map { it.id }
    val uniqueProfiles = config.profiles.distinctBy { it.id }.filter { it.id.isNotBlank() }
    val source = uniqueProfiles.ifEmpty { DefaultSettings.defaultProfiles(categories).profiles }
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
