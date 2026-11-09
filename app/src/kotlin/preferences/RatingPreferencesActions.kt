package org.rankoffline.app


internal fun PreferencesUiController.ratingCategory(value: RatingCategory) = changeRating { current ->
    current.copy(categories = current.categories.map { if (it.id == value.id) value else it })
}


internal fun PreferencesUiController.addRatingCategory(value: RatingCategory) = run {
    val current = settings.state.value
    settings.updateRatingSetup(current.ratingConfig.copy(categories = current.ratingConfig.categories + value),
        current.ratingProfiles.withCategoryAdded(value.id))
    current.activePackId?.let(onPackChanged)
}


internal fun PreferencesUiController.removeRatingCategory(value: RatingCategory) = run {
    val current = settings.state.value
    val packId = requireNotNull(current.activePackId)
    settings.updateRatingSetup(current.ratingConfig.copy(
        categories = current.ratingConfig.categories.filterNot { it.id == value.id }),
        current.ratingProfiles.withCategoryRemoved(value.id))
    ratings.deleteCategoryScores(packId, value.id)
    onPackChanged(packId)
}


internal fun PreferencesUiController.ratingProfiles(value: RatingProfilesConfig) = run { settings.updateRatingProfiles(value) }


internal fun PreferencesUiController.removeProfile(id: String) = run {
    settings.updateRatingProfiles(settings.state.value.ratingProfiles.withProfileDeleted(id))
}


internal fun PreferencesUiController.resetCategories() = run { settings.resetRatingCategories() }


internal fun PreferencesUiController.resetProfiles() = run { settings.resetRatingProfiles() }
