package org.rankoffline.app


internal fun PreferencesUiController.showImages(value: Boolean, all: Boolean) = run {
    if (all) settings.updateAllShowImages(value) else settings.updateShowImages(value)
}

internal fun PreferencesUiController.titlesOnPodium(value: Boolean, all: Boolean) = run {
    if (all) settings.updateAllTitlesOnPodium(value) else settings.updateTitlesOnPodium(value)
}


internal fun PreferencesUiController.stickyScore(value: Boolean, all: Boolean) = run {
    if (all) settings.updateAllStickyScore(value) else settings.updateStickyScore(value)
}


internal fun PreferencesUiController.merge(value: Boolean, all: Boolean) = run {
    if (all) settings.updateAllMergeEnabled(value) else settings.updateMergeEnabled(value)
}


internal fun PreferencesUiController.elaborateRating(value: Boolean) = run { settings.updateElaborateRatingEnabled(value) }

internal fun PreferencesUiController.personalTaste(value: Boolean) = run { settings.updatePersonalTasteEnabled(value) }


internal fun PreferencesUiController.catalogSort(packId: PackId?, value: CatalogSortOrder) = run {
    if (packId == null) settings.updateAllCatalogSortOrder(value)
    else settings.updateCatalogSortOrder(packId, value)
}
