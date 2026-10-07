package com.animerank.offline

import android.content.Context
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
        RatingCategory("writing", "Writing", "Story, pacing, themes, dialogue, consistency, ending", 35.0, "#19D5E5", 0),
        RatingCategory("characters", "Characters", "Main cast, side cast, antagonists, development, depth, dynamics", 25.0, "#E04BCF", 1),
        RatingCategory("engagement", "Engagement", "Emotional impact, entertainment value, viewer investment, tension, atmosphere, memorability", 20.0, "#FF675D", 2),
        RatingCategory("visuals", "Visuals", "Animation, art style, cinematography, backgrounds, effects, character design", 15.0, "#FFAA55", 3),
        RatingCategory("worldbuilding", "Worldbuilding", "Setting, lore, power system, scope, culture, immersion", 5.0, "#62D6C8", 4)
    )

    val defaultAdditiveBonus = AdditiveBonus("Personal taste", "How much you liked it personally, independent of technical quality", 1.0, "#E04BCF")
    val defaultRatingConfig = RatingSystemConfig(
        categories = defaultRatingCategories,
        additiveBonus = defaultAdditiveBonus
    )
}

data class AppSettingsState(
    val showImages: Boolean = true,
    val stickyScore: Boolean = true,
    val ratingConfig: RatingSystemConfig = DefaultSettings.defaultRatingConfig,
    val settingsLoaded: Boolean = false
)

class SettingsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val updateMutex = Mutex()

    private val stickyScoreKey = booleanPreferencesKey("sticky_score")
    private val showImagesKey = booleanPreferencesKey("show_images")
    private val ratingConfigKey = stringPreferencesKey("rating_config")

    private val _state = MutableStateFlow(AppSettingsState(settingsLoaded = false))
    val state: StateFlow<AppSettingsState> = _state.asStateFlow()

    suspend fun loadInitialState() {
        val prefs = context.dataStore.data.first()
        val ratingJson = prefs[ratingConfigKey]
        val ratingConfig = if (ratingJson != null) {
            try { json.decodeFromString<RatingSystemConfig>(ratingJson) } catch (_: Exception) { DefaultSettings.defaultRatingConfig }
        } else {
            DefaultSettings.defaultRatingConfig
        }

        _state.value = AppSettingsState(
            showImages = prefs[showImagesKey] ?: true,
            stickyScore = prefs[stickyScoreKey] ?: true,
            ratingConfig = ratingConfig,
            settingsLoaded = true
        )
    }

    suspend fun updateShowImages(value: Boolean) = updateMutex.withLock {
        _state.value = _state.value.copy(showImages = value)
        context.dataStore.edit { prefs -> prefs[showImagesKey] = value }
    }

    suspend fun updateStickyScore(value: Boolean) = updateMutex.withLock {
        _state.value = _state.value.copy(stickyScore = value)
        context.dataStore.edit { prefs -> prefs[stickyScoreKey] = value }
    }

    suspend fun updateRatingConfig(config: RatingSystemConfig) = updateMutex.withLock {
        _state.value = _state.value.copy(ratingConfig = config)
        context.dataStore.edit { prefs -> prefs[ratingConfigKey] = json.encodeToString(config) }
    }

    suspend fun resetToDefaults() = updateMutex.withLock {
        val config = DefaultSettings.defaultRatingConfig
        _state.value = _state.value.copy(
            showImages = true,
            stickyScore = true,
            ratingConfig = config,
            settingsLoaded = true
        )
        context.dataStore.edit { prefs ->
            prefs[showImagesKey] = true
            prefs[stickyScoreKey] = true
            prefs[ratingConfigKey] = json.encodeToString(config)
        }
    }
}
