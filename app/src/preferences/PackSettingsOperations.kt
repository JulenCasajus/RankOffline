package org.rankoffline.app

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

suspend fun SettingsRepository.updateActivePack(value: PackId) = lock.withLock {
    require(value in manifests) { "Pack not installed: $value" }
    loadPackLocked(value)
    val recent = recentPackOrder(value, recentPackIds.value)
    if (_state.value.activePackId != value || recent != recentPackIds.value) {
        context.dataStore.edit {
            it[activePackKey] = value.value
            it[recentPackKey] = recent.joinToString(",") { id -> id.value.toString() }
        }
    }
    recentPackIds.value = recent
    _state.value = _state.value.copy(activePackId = value).refreshPackSettings().copy(
        catalogSortOrders = currentCatalogSortOrders()
    )
}

internal fun recentPackOrder(active: PackId, previous: List<PackId>): List<PackId> =
    (listOf(active) + previous).distinct().take(5)

suspend fun SettingsRepository.removeRecentPack(value: PackId) = lock.withLock {
    val recent = recentPackIds.value - value
    if (recent.size == recentPackIds.value.size) return@withLock
    context.dataStore.edit {
        it[recentPackKey] = recent.joinToString(",") { id -> id.value.toString() }
    }
    recentPackIds.value = recent
}


/** Virtual ID 0L is stored only as a viewing selection, never a physical PackId. */
suspend fun SettingsRepository.updateLastViewingScope(scope: ViewingScope) = lock.withLock {
    context.dataStore.edit { it[lastViewingScopeKey] = scope.storedId() }
    _state.value = _state.value.copy(lastViewingScope = scope)
}


/** App-wide navigation state: SEARCH, RANKING or SETTINGS. */
suspend fun SettingsRepository.updateLastTab(value: AppTab) = lock.withLock {
    if (_state.value.lastTab == value) return@withLock
    context.dataStore.edit { it[lastTabKey] = value.name }
    _state.value = _state.value.copy(lastTab = value)
}


suspend fun SettingsRepository.updateShowImages(value: Boolean) = changeActive { it.copy(showImages = value) }


suspend fun SettingsRepository.updateStickyScore(value: Boolean) = changeActive { it.copy(stickyScore = value) }


suspend fun SettingsRepository.updateMergeEnabled(value: Boolean) = changeActive {
    require(!value || manifests.getValue(requireActivePackId()).merge.enabled) { "Pack has no group merge" }
    it.copy(mergeEnabled = value)
}


suspend fun SettingsRepository.updateElaborateRatingEnabled(value: Boolean) = changeActive {
    require(!value || manifests.getValue(requireActivePackId()).capabilities.ratings)
    it.copy(elaborateRatingEnabled = value)
}


internal suspend fun SettingsRepository.changeActive(transform: (PersonalPackPreferences) -> PersonalPackPreferences) = lock.withLock {
    val id = requireActivePackId()
    loadPackLocked(id)
    val updated = transform(personal[id] ?: initialPackPreferences(manifests.getValue(id)))
    // Validate effective settings before changing the file or the in-memory state.
    manifests.getValue(id).resolveSettings(effectivePersonal(id, personal + (id to updated)), localRatings[id])
    withContext(Dispatchers.IO) { packFiles.write(id, updated) }
    personal = personal + (id to updated)
    _state.value = _state.value.refreshPackSettings()
}


internal fun SettingsRepository.requireActivePackId(): PackId =
    requireNotNull(_state.value.activePackId) { "No active pack" }
        .also { require(it in manifests) { "Active pack is not installed" } }
