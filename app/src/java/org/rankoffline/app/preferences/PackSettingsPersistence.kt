package org.rankoffline.app

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/** Explicit destructive reset. Ordinary uninstall keeps local/<pack-id> untouched. */
suspend fun SettingsRepository.purgePackSettings(packId: PackId) = lock.withLock {
    withContext(Dispatchers.IO) {
        packFiles.delete(packId)
        ratingFiles.delete(packId)
    }
    personal = personal - packId
    localRatings = localRatings - packId
    loadedPackIds = loadedPackIds - packId
    _state.value = _state.value.copy(
        catalogSortOrders = _state.value.catalogSortOrders - packId
    ).refreshPackSettings()
}


suspend fun SettingsRepository.updateCatalogSortOrder(packId: PackId, order: CatalogSortOrder) = lock.withLock {
    require(packId in manifests) { "Pack not installed: $packId" }
    loadPackLocked(packId)
    val updated = (personal[packId] ?: initialPackPreferences(manifests.getValue(packId)))
        .copy(catalogSortOrder = order)
    withContext(Dispatchers.IO) { packFiles.write(packId, updated) }
    personal = personal + (packId to updated)
    _state.value = _state.value.copy(
        catalogSortOrders = _state.value.catalogSortOrders + (packId to order)
    )
}


/** Reload a hand-edited preferences.json, or recreate it if it was deleted. */
suspend fun SettingsRepository.reloadPackPreferences(packId: PackId) = lock.withLock {
    val manifest = manifests[packId] ?: error("Pack not installed: $packId")
    val loaded = withContext(Dispatchers.IO) {
        packFiles.readOrCreate(packId, initialPackPreferences(manifest))
    }
    personal = personal + (packId to loaded)
    loadedPackIds = loadedPackIds + packId
    _state.value = _state.value.copy(
        catalogSortOrders = _state.value.catalogSortOrders +
            (packId to (loaded.catalogSortOrder ?: CatalogSortOrder.POPULARITY))
    ).refreshPackSettings()
}


/** Reset just the presentation preferences; categories and profiles are untouched. */
suspend fun SettingsRepository.resetPackPreferences(packId: PackId) = lock.withLock {
    val manifest = manifests[packId] ?: error("Pack not installed: $packId")
    val original = initialPackPreferences(manifest)
    withContext(Dispatchers.IO) { packFiles.write(packId, original) }
    personal = personal + (packId to original)
    loadedPackIds = loadedPackIds + packId
    _state.value = _state.value.copy(
        catalogSortOrders = _state.value.catalogSortOrders +
            (packId to (original.catalogSortOrder ?: CatalogSortOrder.POPULARITY))
    ).refreshPackSettings()
}


suspend fun SettingsRepository.updateAllMergeEnabled(value: Boolean) = updateAllView { it.copy(mergeEnabled = value) }


suspend fun SettingsRepository.updateAllShowImages(value: Boolean) = updateAllView { it.copy(showImages = value) }


suspend fun SettingsRepository.updateAllStickyScore(value: Boolean) = updateAllView { it.copy(stickyScore = value) }


suspend fun SettingsRepository.updateAllCatalogSortOrder(value: CatalogSortOrder) =
    updateAllView { it.copy(catalogSortOrder = value) }


internal suspend fun SettingsRepository.updateAllView(transform: (AllViewSettings) -> AllViewSettings) = lock.withLock {
    val updated = transform(_state.value.allViewSettings)
    context.dataStore.edit { it[allViewKey] = settingsJson.encodeToString(updated) }
    _state.value = _state.value.copy(allViewSettings = updated)
}
