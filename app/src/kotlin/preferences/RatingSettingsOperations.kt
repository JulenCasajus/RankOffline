package org.rankoffline.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/** Writes categories and profiles as one atomic local JSON; DataStore is not involved. */
suspend fun SettingsRepository.updateRatingConfig(value: RatingSystemConfig) = editRating { original, current ->
    PackRatingDefinition(
        value,
        reconcileProfiles(current.profiles, value.categories, original.profiles)
    )
}


suspend fun SettingsRepository.updateRatingProfiles(value: RatingProfilesConfig) = editRating { original, current ->
    PackRatingDefinition(current.config, reconcileProfiles(value, current.config.categories, original.profiles))
}


suspend fun SettingsRepository.updateRatingSetup(config: RatingSystemConfig, profiles: RatingProfilesConfig) =
    editRating { original, _ ->
        PackRatingDefinition(config, reconcileProfiles(profiles, config.categories, original.profiles))
    }


/** Reset only category definitions, retaining reconciled personal profile weights. */
suspend fun SettingsRepository.resetRatingCategories() = editRating { original, current ->
    PackRatingDefinition(original.config,
        reconcileProfiles(current.profiles, original.config.categories, original.profiles))
}


/** Reset only profiles, retaining user-edited categories. */
suspend fun SettingsRepository.resetRatingProfiles() = editRating { original, current ->
    PackRatingDefinition(current.config,
        reconcileProfiles(original.profiles, current.config.categories, original.profiles))
}


/** Restores the complete rating.json exactly from the installed pack definition. */
suspend fun SettingsRepository.resetRatingSetup() = editRating { original, _ -> original }


/** Reload hand-edited files, or regenerate them if removed from local/. */
suspend fun SettingsRepository.reloadRatingConfiguration(packId: PackId) = lock.withLock {
    val manifest = manifests[packId] ?: error("Pack not installed: $packId")
    val original = manifest.rating ?: return@withLock
    val loaded = withContext(Dispatchers.IO) { ratingFiles.readOrCreate(packId, original) }
    localRatings = localRatings + (packId to loaded)
    loadedPackIds = loadedPackIds + packId
    _state.value = _state.value.refreshPackSettings()
}


internal suspend fun SettingsRepository.editRating(
    transform: (original: PackRatingDefinition, current: PackRatingDefinition) -> PackRatingDefinition
) = lock.withLock {
    val id = requireActivePackId()
    loadPackLocked(id)
    val original = requireNotNull(manifests.getValue(id).rating) { "Pack does not support ratings" }
    val current = localRatings[id] ?: original
    val updated = transform(original, current)
    validateRatingConfiguration(updated.config, updated.profiles)
    withContext(Dispatchers.IO) { ratingFiles.write(id, updated) }
    localRatings = localRatings + (id to updated)
    _state.value = _state.value.refreshPackSettings()
}
