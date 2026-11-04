package org.rankoffline.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

/** Root UI: owns navigation and screen overlays, never duplicates repository state. */
@Composable
fun RankOfflineApp(viewModel: RankOfflineViewModel) {
    val settings by viewModel.settings.collectAsState()
    val ready by viewModel.ready.collectAsState()
    val scope by viewModel.viewingScope.collectAsState()
    val packs by viewModel.packStates.collectAsState()
    val isDiscoveringPacks by viewModel.discoveringPacks.collectAsState()
    val editor by viewModel.ratingEditor.collectAsState()
    val group by viewModel.groupDetail.collectAsState()
    val list by viewModel.searchResults.collectAsState()
    val searchPage by viewModel.searchPageState.collectAsState()
    val ranked by viewModel.ranking.collectAsState()
    val rankingPage by viewModel.rankingPage.collectAsState()
    val searchState by viewModel.activeSearchState.collectAsState()
    val rankingState by viewModel.activeRankingState.collectAsState()
    val filtersByPack by viewModel.filterOptions.collectAsState()
    val readyManifests = remember(packs) {
        packs.values.filter { it.available }.mapNotNull { it.manifest }
    }
    val combinedOptions = remember(filtersByPack, scope, readyManifests) {
        when (val currentScope = scope) {
            is ViewingScope.Pack -> filtersByPack[currentScope.packId].orEmpty()
            ViewingScope.All -> readyManifests.flatMap { manifest ->
                filtersByPack[manifest.packId].orEmpty().entries
            }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, lists) -> lists.flatten().distinct() }
        }
    }
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf<AppTab?>(null) }
    val activeTab = tab ?: settings.lastTab
    val activePackId = (scope as? ViewingScope.Pack)?.packId
    val activeManifest = activePackId?.let { id -> readyManifests.firstOrNull { it.packId == id } }
    val allSelected = scope == ViewingScope.All
    val allAvailable = readyManifests.size >= 2
    var showPackManagement by rememberSaveable { mutableStateOf(false) }
    var settingsRootRequest by remember { mutableIntStateOf(0) }
    var viewerEntry by remember { mutableStateOf<MediaListEntry?>(null) }
    val services = remember(viewModel) { RankOfflineServices.get(viewModel.getApplication<android.app.Application>()) }
    val covers = remember(services) { services.mediaCoverRepository }
    val bitmapCache = viewModel.coverRepository
    // Remember independently for each pack. The lists are created lazily as packs are opened.
    val searchScroll = remember { mutableMapOf<String, LazyListState>() }
    val rankingScroll = remember { mutableMapOf<String, LazyListState>() }
    val listKey = activePackId?.value?.toString() ?: "__all__"
    val catalogListState = remember(listKey) { searchScroll.getOrPut(listKey) { LazyListState() } }
    val rankingListState = remember(listKey) { rankingScroll.getOrPut(listKey) { LazyListState() } }

    LaunchedEffect(viewModel, context) { viewModel.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
    LaunchedEffect(activeTab) { if (ready && settings.lastTab != activeTab) viewModel.updateLastTab(activeTab) }
    LaunchedEffect(listKey, searchState.query, searchState.filters, searchState.sortOrder) {
        catalogListState.scrollToItem(0)
    }
    LaunchedEffect(listKey, rankingState.filters, rankingState.sortOrder,
        rankingState.descending, rankingState.ratingCategoryId) {
        rankingListState.scrollToItem(0)
    }

    if (!ready || !settings.settingsLoaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    FirstRunOnboarding(settings, packs, isDiscoveringPacks,
        onWelcomeComplete = viewModel::completeWelcome,
        onLanguageSelected = viewModel::updateLanguage,
        onThemeSelected = viewModel::updateThemePalette,
        onDiscoverPacks = viewModel::refreshAvailablePacks,
        onInstallPack = viewModel::downloadPack,
        onCancelInitialCovers = viewModel::cancelInitialCoverDownload,
        onUninstallPack = viewModel::uninstallPackKeepData,
        onPurgePack = viewModel::purgePack,
        onPackSelected = viewModel::updateActivePack,
        onRequestStorageDetails = { viewModel.refreshPackStatistics(it) },
        onOnboardingComplete = viewModel::completeOnboarding
    )

    // Editor is a full-screen overlay, not another eagerly loaded destination.
    editor?.let { current ->
        val draft = current.draft
        if (draft == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        } else RatingScreen(
            editor = current, draft = draft, settings = settings,
            onBack = viewModel::closeRating, onSaveSimple = viewModel::saveSimpleRating,
            onSaveElaborate = viewModel::saveElaborateRating,
            onSaveMetadata = viewModel::saveRatingMetadata, onDelete = viewModel::deleteRating
        )
        return
    }
    group?.let { current ->
        GroupDetailScreen(current, settings, covers, bitmapCache,
            onBack = viewModel::closeGroup,
            onRate = { fromMembers ->
                val candidate = ranked.firstOrNull { it.key == current.group.key }
                    ?: MediaListEntry.Group(current.group, current.explicitRating?.finalScore,
                        current.explicitRating?.qualityScore, current.explicitRating?.additiveScore,
                        null, current.members.size, current.derivedRating.ratedCount,
                        derived = current.explicitRating == null)
                viewModel.openGroupRating(candidate, fromMembers)
            },
            onMember = { viewModel.openRating(MediaListEntry.Item(it)) },
            onCoverDownload = viewModel::downloadCover,
            onCoverOpen = { viewerEntry = it }
        )
        return
    }
    viewerEntry?.let { entry ->
        CoverViewerDialog(entry, settings, covers, bitmapCache,
            onDismiss = { viewerEntry = null },
            onDeleteConfirmed = viewModel::deleteDownloadedCover,
            onDownloadRequested = viewModel::downloadCover,
            onGroupDetails = if (entry is MediaListEntry.Group) ({ viewerEntry = null; viewModel.openGroupDetails(entry.key) }) else null
        )
    }

    val openManagement = { showPackManagement = true; tab = AppTab.SETTINGS }
    val onTab: (AppTab) -> Unit = { target ->
        if (target == activeTab) when (target) {
            AppTab.SEARCH -> coroutines.launch { catalogListState.animateScrollToItem(0) }
            AppTab.RANKING -> coroutines.launch { rankingListState.animateScrollToItem(0) }
            AppTab.SETTINGS -> settingsRootRequest++
        } else tab = target
    }
    Scaffold(snackbarHost = {
        SnackbarHost(snackbar, Modifier.padding(bottom = FloatingNavigationClearance))
    }) { inset ->
        Box(Modifier.fillMaxSize().padding(inset)) {
            CompositionLocalProvider(
                LocalFloatingNavigationClearance provides FloatingNavigationClearance
            ) {
                when (activeTab) {
                AppTab.SEARCH -> if (readyManifests.isEmpty())
                    EmptyPackScreen(stringResource(R.string.app_name), readyManifests, openManagement)
                else CatalogScreen(list, searchPage, searchState, catalogListState,
                    settings, activeManifest, readyManifests, scope,
                    filterOptions = combinedOptions,
                    coverRepository = covers, bitmapCache = bitmapCache,
                    onPack = viewModel::updateActivePack, onAll = viewModel::selectAllPacks,
                    onManage = openManagement, onQuery = viewModel::setSearchQuery,
                    onSort = viewModel::setCatalogSortOrder,
                    onFilters = viewModel::setCatalogFilters,
                    onLoadFilterOptions = {
                        if (allSelected) readyManifests.forEach { viewModel.refreshFilterOptions(it.packId) }
                        else activePackId?.let(viewModel::refreshFilterOptions)
                    },
                    onLoadMore = viewModel::requestNextSearchPage,
                    onMerge = viewModel::updateMergeEnabled,
                    onEntry = viewModel::openRating,
                    onGroup = { viewModel.openGroupDetails(it.key) },
                    onDownloadCover = viewModel::downloadCover, onOpenCover = { viewerEntry = it }
                )
                AppTab.RANKING -> if (readyManifests.isEmpty())
                    EmptyPackScreen(stringResource(R.string.ranking_title), readyManifests, openManagement)
                else RankingScreen(ranked, rankingPage, rankingState, rankingListState,
                    settings, activeManifest, readyManifests, scope,
                    filterOptions = combinedOptions,
                    coverRepository = covers, bitmapCache = bitmapCache,
                    onPack = viewModel::updateActivePack, onAll = viewModel::selectAllPacks,
                    onManage = openManagement, onFilters = viewModel::setRankingFilters,
                    onSort = viewModel::setRankingSortOrder, onSortDirection = viewModel::toggleRankingSortDirection,
                    onCategory = viewModel::setRankingCategory,
                    onLoadFilterOptions = {
                        if (allSelected) readyManifests.forEach { viewModel.refreshFilterOptions(it.packId) }
                        else activePackId?.let(viewModel::refreshFilterOptions)
                    },
                    onLoadMore = viewModel::requestNextRankingPage,
                    onMerge = viewModel::updateMergeEnabled,
                    onEntry = viewModel::openRating, onGroup = { viewModel.openGroupDetails(it.key) },
                    onDownloadCover = viewModel::downloadCover, onOpenCover = { viewerEntry = it }
                )
                AppTab.SETTINGS -> SettingsScreen(settings, viewModel,
                    openCategories = showPackManagement,
                    onCategorySettingsOpened = { showPackManagement = false },
                    rootRequest = settingsRootRequest)
                }
            }
            FloatingNavigationOverlay(activeTab, onTab, Modifier.align(Alignment.BottomCenter))
        }
    }
}
