package org.rankoffline.app

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

suspend fun SettingsRepository.loadInitialState() = lock.withLock {
    if (_state.value.settingsLoaded) return@withLock
    val prefs = context.dataStore.data.first()
    val themeWorkspace = decodeThemeWorkspace(prefs[themeWorkspaceKey])
    val requestedTheme = ThemeSelection.fromPersistedValue(prefs[themeKey], themeWorkspace.customPalettes)
    val selection = safeThemeSelection(requestedTheme, themeWorkspace)
    val selectedIcon = AppIcon.fromPersistedValue(prefs[iconKey])
    val actualIcon = iconAliasController.activeIconOrNull()
    val icon = try {
        if (actualIcon != selectedIcon) iconAliasController.activate(selectedIcon, actualIcon ?: AppIcon.DARK)
        selectedIcon
    } catch (exception: Exception) {
        Log.w("RankOfflineIcon", "Unable to restore launcher icon", exception)
        actualIcon ?: AppIcon.DARK
    }
    val onboardingDone = prefs[onboardingKey] == true
    val welcomeDone = onboardingDone || prefs[welcomeKey] == true
    welcomeChoices = if (welcomeDone) {
        val imageChoice = prefs[welcomeImagesKey]
        val elaborateChoice = prefs[welcomeElaborateKey]
        if (imageChoice != null && elaborateChoice != null) imageChoice to elaborateChoice else null
    } else null
    val preferredPack = prefs[activePackKey]
        ?.let { runCatching { PackId(it) }.getOrNull() }
    val storedRecent = prefs[recentPackKey].orEmpty().split(',')
        .mapNotNull { runCatching { PackId.parse(it) }.getOrNull() }
    recentPackIds.value = preferredPack?.let { recentPackOrder(it, storedRecent) }
        ?: storedRecent.distinct().take(5)
    val language = SupportedLanguage.fromLanguageTag(prefs[languageKey])
    _state.value = AppSettingsState(
        activePackId = preferredPack,
        lastViewingScope = ViewingScope.fromStored(prefs[lastViewingScopeKey] ?: preferredPack?.value),
        lastTab = AppTab.fromPersistedValue(prefs[lastTabKey]),
        allViewSettings = decode<AllViewSettings>(prefs[allViewKey]) ?: AllViewSettings(),
        appIcon = icon,
        themeSelection = selection,
        customPalettes = themeWorkspace.customPalettes,
        builtInPaletteOverrides = themeWorkspace.builtInOverrides,
        hiddenBuiltInPalettes = themeWorkspace.hiddenBuiltIns,
        language = language,
        hasCompletedWelcome = welcomeDone,
        hasCompletedOnboarding = onboardingDone,
        lastSeenWhatsNewVersion = prefs[lastSeenWhatsNewVersionKey],
        settingsLoaded = true
    )
    localeController.apply(language)
}


/**
 * Register installed manifests, then read only the active pack's editable local JSON files.
 * Other packs remain manifest-backed placeholders until selected or explicitly requested.
 * An All-packs screen should register its READY manifests through ensureReadyManifestsLoaded().
 */
suspend fun SettingsRepository.syncInstalledPacks(installed: Collection<PackManifest>) = lock.withLock {
    require(_state.value.settingsLoaded) { "Load preferences before synchronizing packs" }
    val next = installed.associateBy { it.packId }
    require(next.size == installed.size) { "Repeated PackId" }
    manifests = next
    loadedPackIds = loadedPackIds.intersect(next.keys)
    personal = personal.filterKeys { it in next }
    localRatings = localRatings.filterKeys { it in next }
    val chosen = _state.value.activePackId?.takeIf { it in next } ?: next.keys.firstOrNull()
    if (chosen != null) loadPackLocked(chosen)
    _state.value = _state.value.copy(
        activePackId = chosen,
        catalogSortOrders = next.keys.associateWith {
            personal[it]?.catalogSortOrder ?: CatalogSortOrder.POPULARITY
        }
    ).refreshPackSettings()
}


/** Reads exactly one pack on first use, without refreshing any unrelated packs. */
suspend fun SettingsRepository.ensurePackSettingsLoaded(packId: PackId) = lock.withLock {
    require(packId in manifests) { "Pack not installed: $packId" }
    if (loadPackLocked(packId)) publishPackSettingsLocked()
}


/** For an All-packs screen: load its participants only when that screen is opened. */
suspend fun SettingsRepository.ensurePackSettingsLoaded(packIds: Collection<PackId>) = lock.withLock {
    var changed = false
    packIds.distinct().forEach { packId ->
        require(packId in manifests) { "Pack not installed: $packId" }
        if (loadPackLocked(packId)) changed = true
    }
    if (changed) publishPackSettingsLocked()
}


/** A newly READY pack can reach RankOfflineAll before the WorkManager cover stage registers it. */
suspend fun SettingsRepository.ensureReadyManifestsLoaded(ready: Collection<PackManifest>) = lock.withLock {
    var changed = false
    ready.distinctBy { it.packId }.forEach { manifest ->
        val id = manifest.packId
        if (manifests[id] != manifest) {
            manifests = manifests + (id to manifest)
            loadedPackIds = loadedPackIds - id
            changed = true
        }
        if (loadPackLocked(id)) changed = true
    }
    if (changed) publishPackSettingsLocked()
}


internal suspend fun SettingsRepository.loadPackLocked(id: PackId): Boolean {
    if (id in loadedPackIds) return false
    val manifest = manifests.getValue(id)
    // Do not overwrite manually edited invalid files; use manifest defaults for this run.
    val (selected, rating) = withContext(Dispatchers.IO) {
        val selected = try {
            packFiles.readOrCreate(id, initialPackPreferences(manifest))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Log.w("RankOfflinePreferences", "Invalid local preferences for $id; original preserved", exception)
            initialPackPreferences(manifest)
        }
        val rating = manifest.rating?.let { original ->
            try {
                ratingFiles.readOrCreate(id, original)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Log.w("RankOfflineRating", "Invalid local rating file for $id; original preserved", exception)
                original
            }
        }
        selected to rating
    }
    personal = personal + (id to selected)
    if (rating != null) localRatings = localRatings + (id to rating)
    loadedPackIds = loadedPackIds + id
    return true
}


internal fun SettingsRepository.publishPackSettingsLocked() {
    _state.value = _state.value.copy(catalogSortOrders = currentCatalogSortOrders())
        .refreshPackSettings()
}
