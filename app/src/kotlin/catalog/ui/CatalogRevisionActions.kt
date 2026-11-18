package org.rankoffline.app

internal fun CatalogUiController.removePack(packId: PackId) {
    session.remove(packId)
    filterOptionsState.value = filterOptionsState.value - packId
    searchByPack.value = searchByPack.value - packId
    rankingByPack.value = rankingByPack.value - packId
    revisions.catalogChanged(packId)
}


internal fun CatalogUiController.ratingChanged(key: CatalogEntryKey) {
    revisions.ratingsChanged(key.packId)
    // Group rows in Search derive their score and rated-member count from Room.
    revisions.catalogChanged(key.packId)
}


internal fun CatalogUiController.activate(tab: AppTab) {
    if (tab == AppTab.SEARCH || tab == AppTab.RANKING) {
        openedTabs.value = openedTabs.value + tab
    }
}


internal fun CatalogUiController.refreshRatings(packIds: Collection<PackId>) = packIds.forEach(revisions::ratingsChanged)
