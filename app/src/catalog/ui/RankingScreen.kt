package org.rankoffline.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val subtitleFields = remember(installedManifests) {
        installedManifests.associate { it.packId to it.subtitleDefinitions() }
    }
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
                rankingSortIcon(state.sortOrder),
                !state.descending, { sortOpen = true }, onSortDirection) {
              RankOfflinePopupMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                RankingSortOrder.entries.filterNot { it == RankingSortOrder.CATEGORY_SCORE }.forEach { order ->
                    RankOfflinePopupMenuItem(label = order.localizedLabel(),
                        selected = state.sortOrder == order, onClick = {
                            sortOpen = false; onSort(order)
                        }, leadingContent = {
                            Icon(when (order) {
                                RankingSortOrder.FINAL_SCORE -> Icons.Default.Star
                                RankingSortOrder.QUALITY_SCORE -> Icons.Default.AutoAwesome
                                RankingSortOrder.PERSONAL_TASTE -> Icons.Default.Favorite
                                RankingSortOrder.CATEGORY_SCORE -> Icons.Default.Category
                            }, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 12.dp).size(20.dp))
                        })
                }
                if (categories.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.sort_category_score),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    categories.forEach { category ->
                        RankOfflinePopupMenuItem(label = localizedCategoryName(category),
                            selected = state.sortOrder == RankingSortOrder.CATEGORY_SCORE &&
                                state.ratingCategoryId == category.id,
                            onClick = { sortOpen = false; onCategory(category.id) },
                            leadingContent = {
                                Icon(Icons.Default.Category, contentDescription = null,
                                    tint = ratingCategoryMenuColor(category.color),
                                    modifier = Modifier.padding(end = 12.dp).size(20.dp))
                            })
                    }
                }
              }
            }
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
                subtitleFields = subtitleFields[entry.packId].orEmpty(),
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

private fun rankingSortIcon(order: RankingSortOrder) = when (order) {
    RankingSortOrder.FINAL_SCORE -> Icons.Default.Star
    RankingSortOrder.QUALITY_SCORE -> Icons.Default.AutoAwesome
    RankingSortOrder.PERSONAL_TASTE -> Icons.Default.Favorite
    RankingSortOrder.CATEGORY_SCORE -> Icons.Default.Category
}

@Composable
private fun ratingCategoryMenuColor(value: String): Color = try {
    Color(android.graphics.Color.parseColor(value))
} catch (_: IllegalArgumentException) {
    MaterialTheme.colorScheme.primary
}
