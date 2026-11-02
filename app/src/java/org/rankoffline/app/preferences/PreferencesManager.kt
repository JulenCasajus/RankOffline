package org.rankoffline.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal val Context.dataStore by preferencesDataStore(name = "app_preferences")
internal val settingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * App-wide settings use DataStore. Per-pack settings are editable JSON in
 * filesDir/local/<pack-id>/preferences.json; rating definitions in rating/rating.json.
 * Uninstall never removes either file. No legacy data migration is implemented.
 */
class SettingsRepository internal constructor(
    internal val context: Context,
    internal val iconAliasController: AppIconAliasController = PackageManagerAppIconAliasController(context),
    internal val localeController: AppLocaleController = AndroidXAppLocaleController()
) {
    internal val lock = Mutex()
    internal val packFiles = LocalPackPreferencesStore(context)
    internal val ratingFiles = LocalRatingConfigurationStore(context)
    internal var localRatings = emptyMap<PackId, PackRatingDefinition>()
    internal val activePackKey = longPreferencesKey("active_pack_id")
    internal val lastTabKey = stringPreferencesKey("last_tab")
    internal val lastViewingScopeKey = longPreferencesKey("last_viewing_scope")
    internal val allViewKey = stringPreferencesKey("all_view_settings")
    internal val iconKey = stringPreferencesKey("app_icon")
    internal val themeKey = stringPreferencesKey("theme_palette")
    internal val themeWorkspaceKey = stringPreferencesKey("custom_app_palettes")
    internal val languageKey = stringPreferencesKey("language_tag")
    internal val welcomeKey = booleanPreferencesKey("has_completed_welcome")
    internal val onboardingKey = booleanPreferencesKey("has_completed_onboarding")
    internal val welcomeImagesKey = booleanPreferencesKey("welcome_show_images_choice")
    internal val welcomeElaborateKey = booleanPreferencesKey("welcome_elaborate_rating_choice")

    internal var personal = emptyMap<PackId, PersonalPackPreferences>()
    /** Only these packs have had their editable JSON read from disk in this process. */
    internal var loadedPackIds = emptySet<PackId>()
    internal var welcomeChoices: Pair<Boolean, Boolean>? = null
    internal var manifests = emptyMap<PackId, PackManifest>()
    internal val _state = MutableStateFlow(AppSettingsState())
    val state: StateFlow<AppSettingsState> = _state.asStateFlow()

    internal fun currentCatalogSortOrders(): Map<PackId, CatalogSortOrder> =
        manifests.keys.associateWith { personal[it]?.catalogSortOrder ?: CatalogSortOrder.POPULARITY }

    internal fun effectivePersonal(
        id: PackId,
        values: Map<PackId, PersonalPackPreferences> = personal
    ): PersonalPackPreferences {
        val saved = values[id] ?: PersonalPackPreferences()
        val defaults = welcomeChoices ?: return saved
        return saved.copy(
            showImages = saved.showImages ?: defaults.first,
            elaborateRatingEnabled = saved.elaborateRatingEnabled ?: defaults.second
        )
    }

    internal fun AppSettingsState.refreshPackSettings(): AppSettingsState = copy(
        mediaSettings = manifests.mapValues { (id, manifest) ->
            manifest.resolveSettings(effectivePersonal(id), localRatings[id])
        }
    )

    internal fun initialPackPreferences(manifest: PackManifest): PersonalPackPreferences {
        val original = manifest.resolveSettings(PersonalPackPreferences())
        val (welcomeImages, welcomeElaborate) = welcomeChoices ?: (null to null)
        return PersonalPackPreferences(
            showImages = welcomeImages ?: original.showImages,
            stickyScore = original.stickyScore,
            mergeEnabled = original.mergeEnabled,
            useGroupCoversForItems = original.useGroupCoversForItems,
            elaborateRatingEnabled = if (manifest.capabilities.ratings) {
                welcomeElaborate ?: original.elaborateRatingEnabled
            } else false,
            catalogSortOrder = CatalogSortOrder.POPULARITY
        )
    }

    internal inline fun <reified T> decode(value: String?): T? =
        value?.let { runCatching { settingsJson.decodeFromString<T>(it) }.getOrNull() }

    internal fun safeThemeSelection(value: ThemeSelection, workspace: ThemeWorkspace): ThemeSelection {
        val valid = when (value) {
            is ThemeSelection.Custom -> workspace.customPalettes.any { it.id == value.id }
            is ThemeSelection.BuiltIn -> value.palette !in workspace.hiddenBuiltIns
        }
        return if (valid) value else AppThemePalette.entries.firstOrNull {
            it !in workspace.hiddenBuiltIns
        }?.let(ThemeSelection::BuiltIn)
            ?: workspace.customPalettes.firstOrNull()?.let { ThemeSelection.Custom(it.id) }
            ?: ThemeSelection.BuiltIn(AppThemePalette.MIDNIGHT_NEON)
    }
}
