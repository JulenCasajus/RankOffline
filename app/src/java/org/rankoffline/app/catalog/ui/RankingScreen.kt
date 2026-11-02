package org.rankoffline.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Paged ranking. Podium is merely the first 3 visible entries when images are enabled. */
@Composable
internal fun RankingScreen(
    entries: List<MediaListEntry>, page: CatalogUiController.RankingPage,
    state: RankingUiState, listState: LazyListState, settings: AppSettingsState,
    activeManifest: PackManifest?, installedManifests: List<PackManifest>, scope: ViewingScope,
    filterOptions: Map<String, List<CatalogAttributeValue>>,
    coverRepository: CoverRepository, bitmapCache: CoverBitmapCache,
    onPack: (PackId) -> Unit, onAll: () -> Unit, onManage: () -> Unit,
    onFilters: (CatalogFilters) -> Unit, onSort: (RankingSortOrder) -> Unit,
    onSortDirection: () -> Unit, onCategory: (String) -> Unit,
    onLoadFilterOptions: () -> Unit, onLoadMore: () -> Unit, onMerge: (Boolean) -> Unit,
    onEntry: (MediaListEntry) -> Unit, onGroup: (MediaListEntry) -> Unit,
    onDownloadCover: (CatalogEntryKey) -> Unit, onOpenCover: (MediaListEntry) -> Unit
) {
    val all = scope == ViewingScope.All
    val showImages = if (all) settings.allViewSettings.showImages else settings.showImages
    val categories = if (all) compatibleAllRatingCategories(installedManifests.map { it.packId }, settings.mediaSettings)
        else settings.activeMediaSettings?.ratingConfig?.categories.orEmpty().filter { it.active }
    var filtersOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    LaunchedEffect(listState, entries.size, page.hasMore, page.loadingMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .map { it >= entries.size - 5 && entries.isNotEmpty() }
            .distinctUntilChanged().collect { near -> if (near && page.hasMore && !page.loadingMore) onLoadMore() }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().background(Bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = floatingNavigationContentPadding(top = 12.dp, bottom = 12.dp)) {
        item(key = "ranking-controls") {
            PackScreenTitle(stringResource(R.string.ranking_title), installedManifests, settings.activePackId,
                onPack, onManage, allSelected = all, allowAll = installedManifests.size >= 2,
                onAllSelected = onAll)
            Spacer(Modifier.height(10.dp))
            CatalogActions((if (all) installedManifests.any { it.merge.enabled } else activeManifest?.merge?.enabled == true),
                (if (all) settings.allViewSettings.mergeEnabled else settings.activeMediaSettings?.mergeEnabled == true), onMerge,
                "Merge",
                state.filters.activeCount, { filtersOpen = true },
                rankingSortLabel(state.sortOrder, state.ratingCategoryId, categories),
                { sortOpen = true }, onSortDirection)
            Box { DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                RankingSortOrder.entries.filterNot { it == RankingSortOrder.CATEGORY_SCORE }.forEach { order ->
                    DropdownMenuItem(text = { Text(order.localizedLabel()) }, onClick = {
                        sortOpen = false; onSort(order)
                    })
                }
                HorizontalDivider()
                categories.forEach { category ->
                    DropdownMenuItem(text = { Text(localizedCategoryName(category)) }, onClick = {
                        sortOpen = false; onCategory(category.id)
                    })
                }
            } }
        }
        if (showImages && entries.isNotEmpty()) {
            item(key = "ranking-podium") {
                RankingPodium(entries.take(3), state.sortOrder, settings,
                    coverRepository, bitmapCache, onDownloadCover,
                    onOpenCover, onEntry)
            }
        }
        val shown = if (showImages) entries.drop(3) else entries
        itemsIndexed(shown, key = { _, entry -> "${entry.packId.value}:${entry.kind}:${entry.id}" }) { i, entry ->
            MediaEntryRow(entry, settings, coverRepository, bitmapCache,
                showImages = showImages,
                rank = i + if (showImages) 4 else 1,
                rankingSortOrder = state.sortOrder,
                onClick = { onEntry(entry) }, onGroupDetails = { onGroup(entry) },
                onCoverDownload = onDownloadCover, onCoverOpen = { onOpenCover(entry) })
        }
        if (entries.isEmpty()) item { Text(stringResource(if (page.result.failed) R.string.catalog_unavailable_error
            else if (page.result.loading) R.string.loading_list else R.string.no_saved_ratings),
            modifier = Modifier.padding(16.dp)) }
        if (page.loadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }
    if (filtersOpen) CatalogFiltersDialog(activeManifest ?: sharedFilterManifest(installedManifests), state.filters, filterOptions,
        onDismiss = { filtersOpen = false }, onApply = { onFilters(it); filtersOpen = false },
        onLoadOptions = onLoadFilterOptions)
}
