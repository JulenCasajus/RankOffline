package org.rankoffline.app

import kotlinx.coroutines.flow.flow

internal fun CatalogUiController.removePack(packId: PackId) {
    filterOptionsState.value = filterOptionsState.value - packId
    searchByPack.value = searchByPack.value - packId
    rankingByPack.value = rankingByPack.value - packId
    ratingDisplayOverrides.value = ratingDisplayOverrides.value.filterKeys { it.packId != packId }
    revisions.catalogChanged(packId)
}


internal fun CatalogUiController.ratingChanged(key: CatalogEntryKey, draft: RatingDraft) {
    ratingDisplayOverrides.value = ratingDisplayOverrides.value + (key to RatingDisplayOverride(
        finalScore = draft.finalScore,
        qualityScore = draft.qualityScore,
        additiveScore = draft.finalScore?.let { draft.additiveScore }
    ))
    revisions.ratingsChanged(key.packId)
}


internal fun CatalogUiController.activate(tab: AppTab) {
    if (tab == AppTab.SEARCH || tab == AppTab.RANKING) {
        openedTabs.value = openedTabs.value + tab
    }
}


internal fun CatalogUiController.refreshRatings(packIds: Collection<PackId>) = packIds.forEach(revisions::ratingsChanged)
