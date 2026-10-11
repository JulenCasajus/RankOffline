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

object DefaultSettings {
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
}

data class AppSettingsState(
    val showImages: Boolean = false,
    val stickyScore: Boolean = true,
    val elaborateRatingEnabled: Boolean = false,
    val ratingConfig: RatingSystemConfig = DefaultSettings.defaultRatingConfig,
    val appIcon: AppIcon = AppIcon.DARK,
    val language: SupportedLanguage = SupportedLanguage.DEFAULT,
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
    private val appIconKey = stringPreferencesKey("app_icon")
    private val languageKey = stringPreferencesKey("language_tag")

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
        if (normalization.removedCategoryIds.isNotEmpty()) {
            context.dataStore.edit { mutablePrefs ->
                mutablePrefs[ratingConfigKey] = json.encodeToString(ratingConfig)
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
            appIcon = appIcon,
            language = language,
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
        _state.value = _state.value.copy(ratingConfig = config)
        context.dataStore.edit { prefs -> prefs[ratingConfigKey] = json.encodeToString(config) }
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

    suspend fun resetRatingConfig() = updateMutex.withLock {
        val config = DefaultSettings.defaultRatingConfig
        _state.value = _state.value.copy(
            elaborateRatingEnabled = false,
            ratingConfig = config,
            settingsLoaded = true
        )
        context.dataStore.edit { prefs ->
            prefs[elaborateRatingEnabledKey] = false
            prefs[ratingConfigKey] = json.encodeToString(config)
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

internal fun resolveShowImages(persisted: Boolean?): Boolean = persisted ?: false

internal fun resolveElaborateRatingEnabled(persisted: Boolean?): Boolean = persisted ?: false

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
