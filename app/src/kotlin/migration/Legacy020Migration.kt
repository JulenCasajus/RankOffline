package org.rankoffline.app

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** One-time, non-destructive import of the published 0.2.0 personal data. */
internal class Legacy020Migration(private val context: Context) {
    private val marker = context.getSharedPreferences("migration_020", Context.MODE_PRIVATE)
    private val oldDatabase = context.getDatabasePath("anime_rank.db")

    suspend fun run(): Boolean = withContext(Dispatchers.IO) {
        if (marker.getBoolean("complete", false) || !oldDatabase.isFile) return@withContext false
        check(!java.nio.file.Files.isSymbolicLink(oldDatabase.toPath()))
        val data = Legacy020Reader(oldDatabase).read()
        RankOfflineDatabase.getInstance(context).personalDataDao().restore(data, replace = false)
        migrateRatingSettings()
        check(marker.edit().putBoolean("complete", true).commit()) { "Cannot record migration completion" }
        Log.i("RankOfflineMigration", "v0.2.0 personal data imported: " +
            "${data.ratings.size} items, ${data.groupRatings.size} groups")
        true
    }

    private suspend fun migrateRatingSettings() {
        val prefs = context.dataStore.data.first()
        val raw = prefs[stringPreferencesKey("media_settings_v1")]
        val media = raw?.let { settingsJson.parseToJsonElement(it).jsonObject["media"]?.jsonObject }
        val fallbackConfig = prefs[stringPreferencesKey("rating_config")]
        val fallbackProfiles = prefs[stringPreferencesKey("rating_profiles")]
        val names = mapOf("animes" to 1L, "mangas" to 2L, "movies" to 3L,
            "series" to 4L, "books" to 5L)
        val ratings = LocalRatingConfigurationStore(context)
        val preferences = LocalPackPreferencesStore(context)
        names.forEach { (name, value) ->
            val id = PackId(value)
            val old = media?.get(name)?.let { settingsJson.decodeFromJsonElement<MediaSettings>(it) }
            val definition = old?.let { PackRatingDefinition(it.ratingConfig, it.ratingProfiles) }
                ?: if (name == "animes" && fallbackConfig != null && fallbackProfiles != null) {
                    PackRatingDefinition(settingsJson.decodeFromString(fallbackConfig),
                        settingsJson.decodeFromString(fallbackProfiles))
                } else null
            if (definition != null && !ratings.path(id).hasAtomicFile()) ratings.write(id, definition)
            if (old != null && !preferences.path(id).hasAtomicFile()) {
                preferences.write(id, PersonalPackPreferences(
                    showImages = old.showImages, stickyScore = old.stickyScore,
                    mergeEnabled = old.mergeEnabled,
                    elaborateRatingEnabled = old.elaborateRatingEnabled
                ))
            }
        }
    }

    private fun File.hasAtomicFile(): Boolean = exists() ||
        File(path + ".bak").exists() || File(path + ".new").exists()
}
