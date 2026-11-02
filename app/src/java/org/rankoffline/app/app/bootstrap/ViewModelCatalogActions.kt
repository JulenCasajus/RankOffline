package org.rankoffline.app


fun RankOfflineViewModel.setSearchQuery(value: String) = catalog.searchQuery(value)


fun RankOfflineViewModel.requestNextSearchPage() = catalog.nextSearchPage()


fun RankOfflineViewModel.requestNextRankingPage() = catalog.nextRankingPage()


fun RankOfflineViewModel.setCatalogSortOrder(value: CatalogSortOrder) {
    catalog.searchSort(value)
    preferences.catalogSort((viewingScope.value as? ViewingScope.Pack)?.packId, value)
}


fun RankOfflineViewModel.setCatalogSortCriterion(value: CatalogSortOrder) = setCatalogSortOrder(
    value.withReversed(activeSearchState.value.sortOrder.isReversed))


fun RankOfflineViewModel.toggleCatalogSortDirection() = setCatalogSortOrder(activeSearchState.value.sortOrder.inverted())


fun RankOfflineViewModel.setCatalogFilters(value: CatalogFilters) = catalog.searchFilters(value)


fun RankOfflineViewModel.refreshFilterOptions(packId: PackId) = catalog.refreshFilterOptions(packId)


fun RankOfflineViewModel.setRankingFilters(value: CatalogFilters) = catalog.rankingFilters(value)


fun RankOfflineViewModel.setRankingSortOrder(value: RankingSortOrder) = catalog.rankingSort(value)


fun RankOfflineViewModel.toggleRankingSortDirection() = catalog.toggleRankingDirection()


fun RankOfflineViewModel.setRankingCategory(value: String) = catalog.rankingCategory(value)

// Ratings: one identity type for group/item, one Repository.
