package org.rankoffline.app

import androidx.compose.foundation.layout.*
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
    val searchPage by viewModel.searchPageState.collectAsState()
    val rankingPage by viewModel.rankingPage.collectAsState()
    val searchState by viewModel.activeSearchState.collectAsState()
    val rankingState by viewModel.activeRankingState.collectAsState()
    val searchByPack by viewModel.catalog.searchByPack.collectAsState()
    val rankingByPack by viewModel.catalog.rankingByPack.collectAsState()
    val recentPackIds by viewModel.settingsRepository.recentPackIds.collectAsState()
    val ratingOverrides by viewModel.catalog.ratingDisplayOverrides.collectAsState()
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
    val visibleSearchState = activePackId?.let { searchByPack[it] }
        ?: searchState
    val visibleRankingState = activePackId?.let { rankingByPack[it] }
        ?: rankingState
    val visibleSearchPage = if (searchPage.scope == scope &&
        searchPage.query == visibleSearchState.query) searchPage
        else activePackId?.let(viewModel.catalog::cachedSearchPage) ?: SearchPageState(scope = scope)
    val visibleRankingPage = if (rankingPage.result.scope == scope) rankingPage
        else activePackId?.let(viewModel.catalog::cachedRankingPage) ?: CatalogUiController.RankingPage()
    val visibleSearchItems = visibleSearchPage.visibleItemsFor(scope,
        ratingOverrides, visibleSearchState.query)
    val visibleRankingItems = applyRatingOverrides(
        visibleRankingPage.result.visibleItemsFor(scope),
        ratingOverrides)
    val activeManifest = activePackId?.let { id -> readyManifests.firstOrNull { it.packId == id } }
    val allSelected = scope == ViewingScope.All
    val allAvailable = readyManifests.size >= 2
    var showPackManagement by rememberSaveable { mutableStateOf(false) }
    var settingsRootRequest by remember { mutableIntStateOf(0) }
    var viewerEntry by remember { mutableStateOf<MediaListEntry?>(null) }
    val services = remember(viewModel) { RankOfflineServices.get(viewModel.getApplication<android.app.Application>()) }
    val covers = remember(services) { services.mediaCoverRepository }
    val bitmapCache = viewModel.coverRepository
    val catalogListState = viewModel.searchListState(scope)
    val rankingListState = viewModel.rankingListState(scope)

    LaunchedEffect(viewModel, context) { viewModel.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
    LaunchedEffect(activeTab, scope) {
        val selectedPack = scope as? ViewingScope.Pack
        if (selectedPack != null && when (activeTab) {
            AppTab.SEARCH -> viewModel.catalog.cachedSearchPage(selectedPack.packId) == null
            AppTab.RANKING -> viewModel.catalog.cachedRankingPage(selectedPack.packId) == null
            AppTab.SETTINGS -> false
        }) viewModel.catalog.stopPreload()
        viewModel.activateCatalogTab(activeTab)
        if (ready && settings.lastTab != activeTab) viewModel.updateLastTab(activeTab)
    }
    LaunchedEffect(activeTab, scope, recentPackIds, packs,
        searchPage.items.size, rankingPage.result.items.size,
        searchPage.loadingInitial, rankingPage.result.loading) {
        val searchReady = searchPage.scope == scope && !searchPage.loadingInitial &&
            !searchPage.failed && activePackId?.let(viewModel.catalog::cachedSearchPage) != null
        val rankingReady = rankingPage.result.scope == scope &&
            !rankingPage.result.loading && !rankingPage.result.failed &&
            activePackId?.let(viewModel.catalog::cachedRankingPage) != null
        val visible = when (activeTab) {
            AppTab.SEARCH -> searchReady
            AppTab.RANKING -> rankingReady
            AppTab.SETTINGS -> searchReady || rankingReady
        }
        if (packs.values.any { it.installed && !it.available }) viewModel.catalog.stopPreload()
        else if (visible && scope is ViewingScope.Pack) viewModel.preloadRecentCatalogs()
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
        if (draft != null) RatingScreen(
            editor = current, draft = draft, settings = settings,
            onBack = viewModel::closeRating, onSaveSimple = viewModel::saveSimpleRating,
            onSaveElaborate = viewModel::saveElaborateRating,
            onSaveMetadata = viewModel::saveRatingMetadata, onDelete = viewModel::deleteRating
        )
        if (draft != null) return
    }
    group?.let { current ->
        GroupDetailScreen(current, settings, covers, bitmapCache,
            subtitleFields = readyManifests.firstOrNull {
                it.packId == current.group.packId
            }?.subtitleDefinitions().orEmpty(),
            onBack = viewModel::closeGroup,
            onRate = { fromMembers ->
                val candidate = visibleRankingItems.firstOrNull { it.key == current.group.key }
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
                else CatalogScreen(visibleSearchItems, visibleSearchPage, visibleSearchState, catalogListState,
                    settings, activeManifest, readyManifests, scope,
                    filterOptions = combinedOptions,
                    coverRepository = covers, bitmapCache = bitmapCache,
                    onPack = viewModel::updateActivePack, onAll = viewModel::selectAllPacks,
                    onManage = openManagement, onQuery = {
                        viewModel.setSearchQuery(it)
                        coroutines.launch { catalogListState.scrollToItem(0) }
                    },
                    onSort = {
                        viewModel.setCatalogSortOrder(it)
                        coroutines.launch { catalogListState.scrollToItem(0) }
                    },
                    onFilters = {
                        viewModel.setCatalogFilters(it)
                        coroutines.launch { catalogListState.scrollToItem(0) }
                    },
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
                else RankingScreen(visibleRankingItems, visibleRankingPage, visibleRankingState, rankingListState,
                    settings, activeManifest, readyManifests, scope,
                    filterOptions = combinedOptions,
                    coverRepository = covers, bitmapCache = bitmapCache,
                    onPack = viewModel::updateActivePack, onAll = viewModel::selectAllPacks,
                    onManage = openManagement, onFilters = {
                        viewModel.setRankingFilters(it)
                        coroutines.launch { rankingListState.scrollToItem(0) }
                    },
                    onSort = {
                        viewModel.setRankingSortOrder(it)
                        coroutines.launch { rankingListState.scrollToItem(0) }
                    }, onSortDirection = {
                        viewModel.toggleRankingSortDirection()
                        coroutines.launch { rankingListState.scrollToItem(0) }
                    },
                    onCategory = {
                        viewModel.setRankingCategory(it)
                        coroutines.launch { rankingListState.scrollToItem(0) }
                    },
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
