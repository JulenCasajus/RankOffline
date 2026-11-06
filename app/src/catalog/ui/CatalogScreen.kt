package org.rankoffline.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Manifest-driven catalog. No per-category filters and no eager image decoding. */
@Composable
internal fun CatalogScreen(
    entries: List<MediaListEntry>, page: SearchPageState, state: SearchUiState,
    listState: LazyListState, settings: AppSettingsState,
    activeManifest: PackManifest?, installedManifests: List<PackManifest>, scope: ViewingScope,
    filterOptions: Map<String, List<CatalogAttributeValue>>,
    coverRepository: CoverRepository, bitmapCache: CoverBitmapCache,
    onPack: (PackId) -> Unit, onAll: () -> Unit, onManage: () -> Unit,
    onQuery: (String) -> Unit, onSort: (CatalogSortOrder) -> Unit,
    onFilters: (CatalogFilters) -> Unit, onLoadFilterOptions: () -> Unit,
    onLoadMore: () -> Unit, onMerge: (Boolean) -> Unit,
    onEntry: (MediaListEntry) -> Unit, onGroup: (MediaListEntry) -> Unit,
    onDownloadCover: (CatalogEntryKey) -> Unit, onOpenCover: (MediaListEntry) -> Unit
) {
    var filtersOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    val all = scope == ViewingScope.All
    val canMerge = if (all) installedManifests.any { it.merge.enabled } else activeManifest?.merge?.enabled == true
    val merge = if (all) settings.allViewSettings.mergeEnabled else settings.activeMediaSettings?.mergeEnabled == true
    val showImages = if (all) settings.allViewSettings.showImages else settings.showImages
    val subtitleFields = remember(installedManifests) {
        installedManifests.associate { it.packId to it.subtitleDefinitions() }
    }
    LaunchedEffect(listState, entries.size, page.hasMore, page.loadingMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .map { it >= entries.size - 5 && entries.isNotEmpty() }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd && page.hasMore && !page.loadingMore) onLoadMore() }
    }
    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        PackScreenTitle(stringResource(R.string.app_name), installedManifests, settings.activePackId,
            onPack, onManage, allSelected = all, allowAll = installedManifests.size >= 2,
            onAllSelected = onAll)
        Spacer(Modifier.height(10.dp))
        CatalogActions(canMerge, merge, onMerge,
            "Merge",
            state.filters.activeCount,
            { filtersOpen = true }, state.sortOrder.criterionLabel(),
            if (state.sortOrder.withReversed(false) == CatalogSortOrder.POPULARITY)
                Icons.AutoMirrored.Filled.TrendingUp else Icons.Default.SortByAlpha,
            state.sortOrder.isReversed,
            { sortOpen = true }, { onSort(state.sortOrder.inverted()) }) {
          RankOfflinePopupMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
            catalogSortCriteria(activeManifest?.capabilities?.popularity != false).forEach { option ->
                RankOfflinePopupMenuItem(
                    label = option.criterionLabel(),
                    selected = state.sortOrder.withReversed(false) == option,
                    onClick = {
                        sortOpen = false
                        onSort(option.withReversed(state.sortOrder.isReversed))
                    },
                    leadingContent = {
                        Icon(if (option == CatalogSortOrder.POPULARITY) {
                            Icons.AutoMirrored.Filled.TrendingUp
                        } else Icons.Default.SortByAlpha, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 12.dp).size(20.dp))
                    })
            }
          }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = state.query, onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if (state.query.isNotEmpty()) ({ IconButton(onClick = { onQuery("") }) {
                Icon(Icons.Default.Clear, stringResource(R.string.search_clear))
            } }) else null,
            placeholder = { Text(stringResource(R.string.search_catalog_hint)) })
        Spacer(Modifier.height(10.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = floatingNavigationContentPadding(bottom = 24.dp)) {
            items(entries, key = { "${it.packId.value}:${it.kind}:${it.id}" }) { entry ->
                MediaEntryRow(entry, settings, coverRepository, bitmapCache,
                    subtitleFields = subtitleFields[entry.packId].orEmpty(),
                    showImages = showImages, onClick = { onEntry(entry) },
                    onGroupDetails = { onGroup(entry) },
                    onCoverDownload = onDownloadCover, onCoverOpen = { onOpenCover(entry) })
            }
            if (entries.isEmpty()) item { Text(
                stringResource(if (page.failed) R.string.catalog_unavailable_error else
                    if (page.scope != scope || page.loadingInitial) R.string.loading_list else R.string.no_search_results),
                modifier = Modifier.padding(16.dp)) }
            if (page.loadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
    }
    if (filtersOpen) CatalogFiltersDialog(activeManifest ?: sharedFilterManifest(installedManifests), state.filters, filterOptions,
        onDismiss = { filtersOpen = false },
        onApply = { onFilters(it); filtersOpen = false }, onLoadOptions = onLoadFilterOptions)
}
