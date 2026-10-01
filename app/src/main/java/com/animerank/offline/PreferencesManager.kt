package com.animerank.offline

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    val order: Int
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

class PreferencesManager(private val context: Context) {
    companion object {
        private val STICKY_SCORE_KEY = booleanPreferencesKey("sticky_score")
        private val SHOW_IMAGES_KEY = booleanPreferencesKey("show_images")
        private val RATING_CONFIG_KEY = stringPreferencesKey("rating_config")
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun getStickyScore(): Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[STICKY_SCORE_KEY] ?: true
    }

    suspend fun setStickyScore(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[STICKY_SCORE_KEY] = value }
    }

    fun getShowImages(): Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SHOW_IMAGES_KEY] ?: true
    }

    suspend fun setShowImages(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[SHOW_IMAGES_KEY] = value }
    }

    fun getRatingConfig(): Flow<RatingSystemConfig> = context.dataStore.data.map { prefs ->
        val configJson = prefs[RATING_CONFIG_KEY]
        if (configJson != null) {
            try {
                json.decodeFromString(configJson)
            } catch (e: Exception) {
                getDefaultRatingConfig()
            }
        } else {
            getDefaultRatingConfig()
        }
    }

    suspend fun setRatingConfig(config: RatingSystemConfig) {
        context.dataStore.edit { prefs ->
            prefs[RATING_CONFIG_KEY] = json.encodeToString(config)
        }
    }

    suspend fun resetRatingConfig() {
        setRatingConfig(getDefaultRatingConfig())
    }

    fun getDefaultRatingConfig(): RatingSystemConfig {
        return RatingSystemConfig(
            categories = listOf(
                RatingCategory("writing", "Writing", "Story, pacing, themes, dialogue, consistency, ending", 35.0, "#19D5E5", 0),
                RatingCategory("characters", "Characters", "Main cast, side cast, antagonists, development, depth, dynamics", 25.0, "#E04BCF", 1),
                RatingCategory("engagement", "Engagement", "Emotional impact, entertainment value, viewer investment, tension, atmosphere, memorability", 20.0, "#FF675D", 2),
                RatingCategory("visuals", "Visuals", "Animation, art style, cinematography, backgrounds, effects, character design", 15.0, "#FFAA55", 3),
                RatingCategory("worldbuilding", "Worldbuilding", "Setting, lore, power system, scope, culture, immersion", 5.0, "#62D6C8", 4)
            ),
            additiveBonus = AdditiveBonus("Personal taste", "How much you liked it personally, independent of technical quality", 1.0, "#E04BCF")
        )
    }
}
