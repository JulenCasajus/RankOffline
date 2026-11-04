package org.rankoffline.app

import kotlinx.coroutines.flow.flow

internal fun CatalogUiController.nextRankingPage() { moreRanking.tryEmit(Unit) }


internal fun CatalogUiController.rankingFilters(value: CatalogFilters) = editRanking { it.copy(filters = value) }


internal fun CatalogUiController.rankingSort(value: RankingSortOrder) = editRanking {
    it.copy(sortOrder = value, ratingCategoryId = it.ratingCategoryId.takeIf { value == RankingSortOrder.CATEGORY_SCORE })
}


internal fun CatalogUiController.rankingCategory(value: String) = editRanking {
    it.copy(ratingCategoryId = value, sortOrder = RankingSortOrder.CATEGORY_SCORE)
}


internal fun CatalogUiController.toggleRankingDirection() = editRanking { it.copy(descending = !it.descending) }


internal fun CatalogUiController.editRanking(update: (RankingUiState) -> RankingUiState) {
    when (val selected = viewingScope.value) {
        ViewingScope.All -> allRanking.value = update(allRanking.value)
        is ViewingScope.Pack -> rankingByPack.value = rankingByPack.value +
            (selected.packId to update(rankingByPack.value[selected.packId] ?: RankingUiState()))
    }
}
