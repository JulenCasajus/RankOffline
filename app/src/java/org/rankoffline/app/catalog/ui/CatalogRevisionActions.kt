package org.rankoffline.app

import kotlinx.coroutines.flow.flow

internal fun CatalogUiController.removePack(packId: PackId) {
    filterOptionsState.value = filterOptionsState.value - packId
    searchByPack.value = searchByPack.value - packId
    rankingByPack.value = rankingByPack.value - packId
    ratingDisplayOverrides.value = ratingDisplayOverrides.value.filterKeys { it.packId != packId }
    revisions.catalogChanged(packId)
}


internal fun CatalogUiController.ratingChanged(key: CatalogEntryKey) {
    ratingDisplayOverrides.value = ratingDisplayOverrides.value - key
    revisions.ratingsChanged(key.packId)
}


internal fun CatalogUiController.refreshRatings(packIds: Collection<PackId>) = packIds.forEach(revisions::ratingsChanged)
