package org.rankoffline.app

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

suspend fun SettingsRepository.updateLanguage(value: SupportedLanguage) = lock.withLock {
    context.dataStore.edit { it[languageKey] = value.languageTag }
    _state.value = _state.value.copy(language = value)
    localeController.apply(value)
}


/** Persist the user's welcome choices even when no packs are installed yet. */
suspend fun SettingsRepository.completeWelcome(showImages: Boolean, elaborateRating: Boolean) = lock.withLock {
    // This is infrequent; read existing customizations before applying onboarding globally.
    manifests.keys.forEach { loadPackLocked(it) }
    // Welcome can run after a pack has already been installed. Apply its choices to
    // currently installed packs as well; future installations inherit the choices.
    val updates = manifests.mapValues { (id, manifest) ->
        val current = personal[id] ?: initialPackPreferences(manifest)
        current.copy(
            showImages = showImages,
            elaborateRatingEnabled = if (manifest.capabilities.ratings) elaborateRating else false
        )
    }
    withContext(Dispatchers.IO) {
        updates.forEach { (id, value) -> packFiles.write(id, value) }
    }
    context.dataStore.edit { prefs ->
        prefs[welcomeImagesKey] = showImages
        prefs[welcomeElaborateKey] = elaborateRating
        prefs[welcomeKey] = true
    }
    personal = personal + updates
    welcomeChoices = showImages to elaborateRating
    _state.value = _state.value.copy(hasCompletedWelcome = true).refreshPackSettings()
}


suspend fun SettingsRepository.completeOnboarding() = lock.withLock {
    context.dataStore.edit {
        it[welcomeKey] = true
        it[onboardingKey] = true
    }
    _state.value = _state.value.copy(
        hasCompletedWelcome = true, hasCompletedOnboarding = true
    )
}


/** Personal backup API. Definitions remain owned by installed manifests, not by the backup. */
suspend fun SettingsRepository.applyImportedPersonalSettings(
    imported: Map<PackId, MediaSettings>,
    importedWorkspace: ThemeWorkspace,
    importedSortOrders: Map<PackId, CatalogSortOrder>,
    importedAllViewSettings: AllViewSettings,
    importedThemeSelection: String,
    replace: Boolean
) = lock.withLock {
    val importedPersonal = imported.mapValues { (id, item) ->
        PersonalPackPreferences(
            showImages = item.showImages,
            stickyScore = item.stickyScore,
            elaborateRatingEnabled = item.elaborateRatingEnabled,
            mergeEnabled = item.mergeEnabled,
            catalogSortOrder = importedSortOrders[id]
        )
    }
    // Backup's rating definitions live in local files, not in the DataStore snapshot.
    // Import first, and do not purge unrelated local files automatically.
    val importedRatings = imported.mapValues { (_, item) ->
        PackRatingDefinition(item.ratingConfig, item.ratingProfiles)
    }.filterValues { it.config.categories.isNotEmpty() }
    importedRatings.values.forEach { validateRatingConfiguration(it.config, it.profiles) }
    withContext(Dispatchers.IO) {
        importedRatings.forEach { (id, value) -> ratingFiles.write(id, value) }
    }
    localRatings = localRatings + importedRatings.filterKeys { it in manifests }
    // A backup may reference uninstalled packs: preserve those files for reinstall.
    // Never delete unrelated local files merely because they are absent from a backup.
    val importedIds = imported.keys + importedSortOrders.keys
    val changed = withContext(Dispatchers.IO) {
        importedIds.associateWith { id ->
            val existing = if (replace) PersonalPackPreferences() else
                (packFiles.readOrNull(id) ?: personal[id] ?: PersonalPackPreferences())
            val entry = importedPersonal[id] ?: existing
            val updated = entry.copy(catalogSortOrder = importedSortOrders[id] ?: entry.catalogSortOrder)
            packFiles.write(id, updated)
            updated
        }
    }
    val newPersonal = personal + changed.filterKeys { it in manifests }
    val newSort = newPersonal.mapValues { it.value.catalogSortOrder ?: CatalogSortOrder.POPULARITY }
    val workspace = if (replace) importedWorkspace else ThemeWorkspace(
        customPalettes = (_state.value.customPalettes + importedWorkspace.customPalettes)
            .distinctBy { it.id },
        builtInOverrides = _state.value.builtInPaletteOverrides + importedWorkspace.builtInOverrides,
        hiddenBuiltIns = _state.value.hiddenBuiltInPalettes + importedWorkspace.hiddenBuiltIns
    )
    val allView = if (replace) importedAllViewSettings else _state.value.allViewSettings
    val chosen = safeThemeSelection(
        ThemeSelection.fromPersistedValue(
            if (replace) importedThemeSelection else _state.value.themeSelection.persistedValue,
            workspace.customPalettes
        ), workspace
    )
    context.dataStore.edit { prefs ->
        prefs[allViewKey] = settingsJson.encodeToString(allView)
        prefs[themeWorkspaceKey] = encodeThemeWorkspace(workspace)
        prefs[themeKey] = chosen.persistedValue
    }
    personal = newPersonal
    _state.value = _state.value.copy(
        catalogSortOrders = newSort,
        allViewSettings = allView,
        customPalettes = workspace.customPalettes,
        builtInPaletteOverrides = workspace.builtInOverrides,
        hiddenBuiltInPalettes = workspace.hiddenBuiltIns,
        themeSelection = chosen
    ).refreshPackSettings()
}
