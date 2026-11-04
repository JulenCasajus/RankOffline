package org.rankoffline.app

import kotlinx.coroutines.flow.flow

internal fun CatalogUiController.nextSearchPage() { moreSearch.tryEmit(Unit) }


internal fun CatalogUiController.searchQuery(value: String) = editSearch { it.copy(query = value) }


internal fun CatalogUiController.searchSort(value: CatalogSortOrder) = editSearch { it.copy(sortOrder = value) }


internal fun CatalogUiController.searchFilters(value: CatalogFilters) = editSearch { it.copy(filters = value) }


internal fun CatalogUiController.editSearch(update: (SearchUiState) -> SearchUiState) {
    when (val selected = viewingScope.value) {
        ViewingScope.All -> allSearch.value = update(allSearch.value)
        is ViewingScope.Pack -> searchByPack.value = searchByPack.value +
            (selected.packId to update(searchByPack.value[selected.packId] ?: SearchUiState()))
    }
}


internal fun CatalogUiController.preparePack(packId: PackId, sortOrder: CatalogSortOrder) {
    if (packId !in searchByPack.value) searchByPack.value = searchByPack.value +
        (packId to SearchUiState(sortOrder = sortOrder))
}
