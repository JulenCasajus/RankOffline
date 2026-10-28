package org.rankoffline.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

internal val Bg: Color
    @Composable get() = MaterialTheme.colorScheme.background
internal val Panel: Color
    @Composable get() = MaterialTheme.colorScheme.surface
internal val Cyan = Color(0xFF19D5E5)
internal val Magenta = Color(0xFFE04BCF)
internal val Coral = Color(0xFFFF675D)
internal val Orange = Color(0xFFFFAA55)
internal val Mint = Color(0xFF62D6C8)
class MainActivity : AppCompatActivity() {
    private val appViewModel: RankOfflineViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by appViewModel.settings.collectAsState()
            RankOfflineTheme(settings.activePalette) {
                RankOfflineApp(appViewModel)
            }
        }
    }
}
private data class CoverViewerState(
    val displayAnime: Anime,
    val coverAnime: Anime
)

@Composable
fun RankOfflineApp(viewModel: RankOfflineViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var coverViewer by remember { mutableStateOf<CoverViewerState?>(null) }
    val settings by viewModel.settings.collectAsState()
    val ready by viewModel.ready.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val ratingEditor by viewModel.ratingEditor.collectAsState()
    val groupDetail by viewModel.groupDetail.collectAsState()
    val list by viewModel.searchResults.collectAsState()
    val searchPageState by viewModel.searchPageState.collectAsState()
    val ranked by viewModel.ranking.collectAsState()
    val rankingResultState by viewModel.rankingResultState.collectAsState()
    val searchState by viewModel.activeSearchState.collectAsState()
    val rankingState by viewModel.activeRankingState.collectAsState()
    val query = searchState.query
    val catalogSortOrder = searchState.sortOrder
    val catalogFilters = searchState.filters
    val rankingFilters = rankingState.filters
    val rankingSortOrder = rankingState.sortOrder
    val rankingSortDescending = rankingState.descending
    val rankingCategoryId = rankingState.categoryId
    val filterOptions by viewModel.filterOptions.collectAsState()
    val genericFilterOptionsByMedia by viewModel.genericFilterOptions.collectAsState()
    val catalogAvailabilityByMedia by viewModel.catalogAvailability.collectAsState()
    val categoryPackStates by viewModel.categoryPackStates.collectAsState()
    val categoryDownloadProgress by viewModel.categoryDownloadProgress.collectAsState()
    val viewingScope by viewModel.viewingScope.collectAsState()
    val allAvailable by viewModel.allAvailable.collectAsState()
    val allSelected = viewingScope == ViewingScope.All
    val availableMediaTypes = categoryPackStates.values
        .filter(CategoryPackState::available)
        .mapTo(mutableSetOf(), CategoryPackState::mediaType)
    val noInstalledPacks = availableMediaTypes.isEmpty()
    val genericFilterOptions = if (allSelected) {
        combineGenericFilterOptions(
            availableMediaTypes.mapNotNull(genericFilterOptionsByMedia::get)
        )
    } else genericFilterOptionsByMedia[settings.activeMediaType] ?: GenericMediaFilterOptions()
    val rankingCategories = if (allSelected) {
        compatibleAllRatingCategories(availableMediaTypes, settings.mediaSettings)
    } else {
        settings.ratingConfig.categories.filter(RatingCategory::active)
    }
    val catalogAvailability = if (allSelected && allAvailable) CatalogAvailability.AVAILABLE
    else catalogAvailabilityByMedia[settings.activeMediaType] ?: CatalogAvailability.UNAVAILABLE
    val catalogCount by viewModel.catalogCount.collectAsState()
    val catalogListStates = remember { MediaType.entries.associateWith { LazyListState() } }
    val rankingListStates = remember { MediaType.entries.associateWith { LazyListState() } }
    val allCatalogListState = remember { LazyListState() }
    val allRankingListState = remember { LazyListState() }
    val catalogListState = if (allSelected) allCatalogListState
    else catalogListStates.getValue(settings.activeMediaType)
    val rankingListState = if (allSelected) allRankingListState
    else rankingListStates.getValue(settings.activeMediaType)
    val previousCatalogStates = remember { mutableStateMapOf<MediaType, SearchUiState>() }
    var pendingCatalogTopReset by remember { mutableStateOf<MediaType?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()
    var openCategorySettings by remember { mutableStateOf(false) }
    var settingsRootRequest by remember { mutableStateOf(0) }
    val routeUnavailableCategory: (MediaType) -> Unit = {
        openCategorySettings = true
        tab = 2
    }

    LaunchedEffect(settings.activeMediaType, searchState) {
        val mediaType = settings.activeMediaType
        val previous = previousCatalogStates.put(mediaType, searchState)
        if (previous != null && previous != searchState) {
            pendingCatalogTopReset = mediaType
            catalogListState.scrollToItem(0)
        }
    }
    LaunchedEffect(list, settings.activeMediaType) {
        if (pendingCatalogTopReset == settings.activeMediaType) {
            catalogListState.scrollToItem(0)
            pendingCatalogTopReset = null
        }
    }

    LaunchedEffect(viewModel, context) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it.resolve(context)) }
    }

    if (!ready || !settings.settingsLoaded) {
        Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    FirstRunOnboarding(
        settings = settings,
        categoryPacks = categoryPackStates,
        downloadProgress = categoryDownloadProgress,
        onWelcomeComplete = viewModel::completeWelcome,
        onLanguageSelected = viewModel::updateLanguage,
        onThemeSelected = viewModel::updateThemePalette,
        onDownloadCategory = viewModel::downloadCategory,
        onCancelCategoryCoverDownload = viewModel::cancelInitialCoverDownload,
        onRemoveCategory = viewModel::removeCategoryCatalog,
        onPurgeCategory = { viewModel.purgePack(it.toPackId()) },
        onMediaTypeSelected = viewModel::updateActiveMediaType,
        onOnboardingComplete = viewModel::completeOnboarding
    )

    coverViewer?.let { viewer ->
        CoverViewerDialog(
            coverAnime = viewer.coverAnime,
            displayAnime = viewer.displayAnime,
            coverRepository = viewModel.coverRepository,
            genericCoverRepository = viewModel.genericCoverRepository,
            genericGroupCoverRepository = viewModel.genericGroupCoverRepository,
            onDismiss = { coverViewer = null },
            onGroupDetails = if (viewer.displayAnime.usesGroupEntriesCoverSubtitle()) {
                {
                    coverViewer = null
                    viewModel.openGroupDetails(viewer.displayAnime)
                }
            } else {
                null
            },
            onDeleteConfirmed = { onResult ->
                viewModel.deleteDownloadedCover(viewer.coverAnime) { deleted ->
                    if (deleted) coverViewer = null
                    onResult(deleted)
                }
            }
        )
    }

    ratingEditor?.let { editor ->
        if (editor.draft == null) {
            Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            RatingScreen(
                anime = editor.anime,
                groupScore = usesGroupScoreLabel(editor.isGroup, editor.anime.memberCount),
                initial = editor.draft,
                settings = settings.withActiveMediaType(editor.anime.mediaType),
                onBack = viewModel::closeRating,
                onSaveElaborate = { draft, finalOverride, profileId ->
                    viewModel.saveElaborateRating(editor.anime.id, draft, finalOverride, profileId)
                },
                onSaveSimple = { draft, quality, final ->
                    viewModel.saveSimpleRating(editor.anime.id, draft, quality, final)
                },
                onDelete = { viewModel.deleteRating(editor.anime.id) }
            )
        }
        return
    }

    groupDetail?.let { detail ->
        val sourceSettings = settings.mediaSettings.getValue(detail.mediaType)
        GroupDetailScreen(
            detail = detail,
            imagesEnabled = sourceSettings.showImages,
            oneCoverPerGroup = sourceSettings.oneCoverPerGroup,
            coverRepository = viewModel.coverRepository,
            genericCoverRepository = viewModel.genericCoverRepository,
            genericGroupCoverRepository = viewModel.genericGroupCoverRepository,
            onBack = viewModel::closeGroup,
            onRate = viewModel::rateGroup,
            onMember = viewModel::openRating,
            onCoverDownload = { viewModel.downloadCover(it.coverTarget(sourceSettings.oneCoverPerGroup)) },
            onCoverOpen = {
                coverViewer = CoverViewerState(it, it.coverTarget(sourceSettings.oneCoverPerGroup))
            }
        )
        return
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                val navigationItemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary
                )
                NavigationBarItem(selected = tab == 0, onClick = {
                    if (bottomNavigationAction(tab, 0) == BottomNavigationAction.RESELECT) {
                        appScope.launch { catalogListState.animateScrollToItem(0) }
                    } else tab = 0
                }, icon = { Icon(Icons.Default.Search, null) }, label = {
                    Text(
                        stringResource(
                            searchNavigationLabelResource(
                                noInstalledPacks,
                                settings.activeMediaType,
                                allSelected
                            )
                        )
                    )
                }, colors = navigationItemColors)
                NavigationBarItem(selected = tab == 1, onClick = {
                    if (bottomNavigationAction(tab, 1) == BottomNavigationAction.RESELECT) {
                        appScope.launch { rankingListState.animateScrollToItem(0) }
                    } else tab = 1
                }, icon = { Icon(Icons.Default.EmojiEvents, null) }, label = { Text(stringResource(R.string.nav_ranking)) }, colors = navigationItemColors)
                NavigationBarItem(selected = tab == 2, onClick = {
                    if (bottomNavigationAction(tab, 2) == BottomNavigationAction.RESELECT) {
                        settingsRootRequest++
                    } else {
                        tab = 2
                    }
                }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text(stringResource(R.string.nav_settings)) }, colors = navigationItemColors)
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> if (noInstalledPacks) NoInstalledPacksScreen(
                    title = stringResource(R.string.app_name),
                    activeMediaType = settings.activeMediaType,
                    onMediaTypeSelected = viewModel::updateActiveMediaType,
                    onUnavailableMediaType = routeUnavailableCategory
                ) else AnimeListScreen(
                    list = list,
                    listState = catalogListState,
                    activeMediaType = settings.activeMediaType,
                    availableMediaTypes = availableMediaTypes,
                    onMediaTypeSelected = viewModel::updateActiveMediaType,
                    allAvailable = allAvailable,
                    allSelected = allSelected,
                    onAllSelected = viewModel::selectAllMedia,
                    onUnavailableMediaType = routeUnavailableCategory,
                    query = query,
                    imagesEnabled = if (allSelected) settings.allViewSettings.showImages else settings.showImages,
                    oneCoverPerGroup = { mediaType ->
                        settings.mediaSettings.getValue(mediaType).oneCoverPerGroup
                    },
                    coverRepository = viewModel.coverRepository,
                    genericCoverRepository = viewModel.genericCoverRepository,
                    genericGroupCoverRepository = viewModel.genericGroupCoverRepository,
                    onQueryChange = viewModel::setSearchQuery,
                    sortOrder = catalogSortOrder,
                    onSortOrderChange = viewModel::setCatalogSortCriterion,
                    onSortDirectionToggle = viewModel::toggleCatalogSortDirection,
                    filters = catalogFilters,
                    genericFilters = searchState.genericFilters,
                    mergeSeasonsEnabled = !allSelected && settings.mergeSeasonsEnabled,
                    onMergeSeasonsChange = viewModel::updateMergeSeasonsEnabled,
                    filterOptions = filterOptions,
                    genericFilterOptions = genericFilterOptions,
                    onFiltersChange = viewModel::setCatalogFilters,
                    onGenericFiltersChange = viewModel::setGenericCatalogFilters,
                    onAnime = viewModel::openAnime,
                    onGroupDetails = viewModel::openGroupDetails,
                    onCoverDownload = { item ->
                        val shared = settings.mediaSettings.getValue(item.mediaType).oneCoverPerGroup
                        viewModel.downloadCover(item.coverTarget(shared))
                    },
                    onCoverOpen = {
                        val shared = settings.mediaSettings.getValue(it.mediaType).oneCoverPerGroup
                        coverViewer = CoverViewerState(it, it.coverTarget(shared))
                    },
                    errorMessage = errorMessage,
                    catalogAvailability = catalogAvailability,
                    listLoading = if (allSelected) searchPageState.loadingInitial else
                        searchPageState.mediaType != settings.activeMediaType || searchPageState.loadingInitial,
                    listFailed = if (allSelected) searchPageState.failed else
                        searchPageState.mediaType == settings.activeMediaType && searchPageState.failed,
                    loadingMore = searchPageState.loadingMore,
                    hasMore = searchPageState.hasMore,
                    onLoadMore = viewModel::requestNextSearchPage
                )
                1 -> if (noInstalledPacks) NoInstalledPacksScreen(
                    title = stringResource(R.string.ranking_title),
                    activeMediaType = settings.activeMediaType,
                    onMediaTypeSelected = viewModel::updateActiveMediaType,
                    onUnavailableMediaType = routeUnavailableCategory
                ) else RankingScreen(
                    ranked = ranked,
                    listState = rankingListState,
                    activeMediaType = settings.activeMediaType,
                    availableMediaTypes = availableMediaTypes,
                    onMediaTypeSelected = viewModel::updateActiveMediaType,
                    allAvailable = allAvailable,
                    allSelected = allSelected,
                    onAllSelected = viewModel::selectAllMedia,
                    onUnavailableMediaType = routeUnavailableCategory,
                    imagesEnabled = if (allSelected) settings.allViewSettings.showImages else settings.showImages,
                    oneCoverPerGroup = { mediaType ->
                        settings.mediaSettings.getValue(mediaType).oneCoverPerGroup
                    },
                    coverRepository = viewModel.coverRepository,
                    genericCoverRepository = viewModel.genericCoverRepository,
                    genericGroupCoverRepository = viewModel.genericGroupCoverRepository,
                    filters = rankingFilters,
                    genericFilters = rankingState.genericFilters,
                    mergeSeasonsEnabled = !allSelected && settings.mergeSeasonsEnabled,
                    onMergeSeasonsChange = viewModel::updateMergeSeasonsEnabled,
                    filterOptions = filterOptions,
                    genericFilterOptions = genericFilterOptions,
                    sortOrder = rankingSortOrder,
                    sortDescending = rankingSortDescending,
                    categoryId = rankingCategoryId,
                    categories = rankingCategories,
                    onFiltersChange = viewModel::setRankingFilters,
                    onGenericFiltersChange = viewModel::setGenericRankingFilters,
                    onSortOrderChange = viewModel::setRankingSortOrder,
                    onSortDirectionToggle = viewModel::toggleRankingSortDirection,
                    onCategorySort = viewModel::setRankingCategory,
                    onAnime = viewModel::openAnime,
                    onGroupDetails = viewModel::openGroupDetails,
                    onCoverDownload = { item ->
                        val shared = settings.mediaSettings.getValue(item.mediaType).oneCoverPerGroup
                        viewModel.downloadCover(item.coverTarget(shared))
                    },
                    onCoverOpen = {
                        val shared = settings.mediaSettings.getValue(it.mediaType).oneCoverPerGroup
                        coverViewer = CoverViewerState(it, it.coverTarget(shared))
                    },
                    catalogAvailability = catalogAvailability,
                    listLoading = if (allSelected) rankingResultState.loading else
                        rankingResultState.mediaType != settings.activeMediaType || rankingResultState.loading,
                    listFailed = if (allSelected) rankingResultState.failed else
                        rankingResultState.mediaType == settings.activeMediaType && rankingResultState.failed
                )
                else -> SettingsScreen(
                    settings,
                    catalogCount,
                    categoryPackStates,
                    availableMediaTypes,
                    categoryDownloadProgress,
                    openCategorySettings,
                    onCategorySettingsOpened = { openCategorySettings = false },
                    rootRequest = settingsRootRequest,
                    viewModel = viewModel
                )
            }
        }
    }
}

internal fun searchNavigationLabelResource(
    noInstalledPacks: Boolean,
    activeMediaType: MediaType,
    allSelected: Boolean = false
): Int = when {
    noInstalledPacks -> R.string.nav_search
    allSelected -> R.string.media_all
    else -> activeMediaType.labelResource
}

internal fun combineGenericFilterOptions(
    options: List<GenericMediaFilterOptions>
): GenericMediaFilterOptions = GenericMediaFilterOptions(
    genres = options.flatMap(GenericMediaFilterOptions::genres).distinct().sorted(),
    formats = options.flatMap(GenericMediaFilterOptions::formats).distinct().sorted(),
    years = options.flatMap(GenericMediaFilterOptions::years).distinct().sortedDescending(),
    creditNames = options.flatMap(GenericMediaFilterOptions::creditNames).distinct().sorted(),
    demographics = options.flatMap(GenericMediaFilterOptions::demographics).distinct().sorted()
)
@Composable
private fun NoInstalledPacksScreen(
    title: String,
    activeMediaType: MediaType,
    onMediaTypeSelected: (MediaType) -> Unit,
    onUnavailableMediaType: (MediaType) -> Unit
) {
    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        MediaScreenTitle(
            title = title,
            activeMediaType = activeMediaType,
            onMediaTypeSelected = onMediaTypeSelected,
            availableMediaTypes = emptySet(),
            onDownloadPack = { onUnavailableMediaType(activeMediaType) },
            noPacksInstalled = true
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.packs_empty_invitation),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(24.dp)
            )
        }
    }
}

@Composable
private fun AnimeListScreen(
    list: List<Anime>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    activeMediaType: MediaType,
    availableMediaTypes: Set<MediaType>,
    onMediaTypeSelected: (MediaType) -> Unit,
    allAvailable: Boolean,
    allSelected: Boolean,
    onAllSelected: () -> Unit,
    onUnavailableMediaType: (MediaType) -> Unit,
    query: String,
    imagesEnabled: Boolean,
    oneCoverPerGroup: (MediaType) -> Boolean,
    coverRepository: CoverBitmapCache,
    genericCoverRepository: GenericMediaCoverRepository? = null,
    genericGroupCoverRepository: GenericMediaGroupCoverRepository? = null,
    onQueryChange: (String) -> Unit,
    sortOrder: CatalogSortOrder,
    onSortOrderChange: (CatalogSortOrder) -> Unit,
    onSortDirectionToggle: () -> Unit,
    filters: AnimeFilters,
    genericFilters: GenericMediaFilters,
    mergeSeasonsEnabled: Boolean,
    onMergeSeasonsChange: (Boolean) -> Unit,
    filterOptions: AnimeFilterOptions,
    genericFilterOptions: GenericMediaFilterOptions,
    onFiltersChange: (AnimeFilters) -> Unit,
    onGenericFiltersChange: (GenericMediaFilters) -> Unit,
    onAnime: (Anime) -> Unit,
    onGroupDetails: (Anime) -> Unit,
    onCoverDownload: (Anime) -> Unit,
    onCoverOpen: (Anime) -> Unit,
    errorMessage: UserMessage?,
    catalogAvailability: CatalogAvailability,
    listLoading: Boolean,
    listFailed: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val visibleLocalItems = list

    LaunchedEffect(listState, visibleLocalItems.size, hasMore, loadingMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .map { lastVisible ->
                visibleLocalItems.isNotEmpty() && lastVisible >= visibleLocalItems.lastIndex - 5
            }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd && hasMore && !loadingMore) onLoadMore()
            }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .dismissFocusOnUnconsumedTap(focusManager)
            .padding(16.dp)
    ) {
        MediaScreenTitle(
            title = stringResource(R.string.app_name),
            activeMediaType = activeMediaType,
            onMediaTypeSelected = onMediaTypeSelected,
            availableMediaTypes = availableMediaTypes,
            onDownloadPack = { onUnavailableMediaType(activeMediaType) },
            allAvailable = allAvailable,
            allSelected = allSelected,
            onAllSelected = onAllSelected
        )
        Spacer(Modifier.height(10.dp))
        ListActionRow(
            mergeLabel = if (allSelected) null else
                activeMediaType.mergeLabelResource?.let { stringResource(it) },
            mergeSeasonsEnabled = mergeSeasonsEnabled,
            onMergeSeasonsChange = onMergeSeasonsChange,
            activeFilterCount = if (!allSelected && activeMediaType == MediaType.ANIME) filters.activeCount
            else genericFilters.activeCount,
            sortLabel = sortOrder.criterionLabel(),
            sortReversed = sortOrder.isReversed,
            onFilters = { showFilters = true },
            onSort = { sortMenuExpanded = true },
            onSortDirectionToggle = onSortDirectionToggle
        ) {
                RankOfflinePopupMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    catalogSortCriteria().forEach { option ->
                        RankOfflinePopupMenuItem(
                            label = option.criterionLabel(),
                            selected = sortOrder.withReversed(false) == option,
                            onClick = {
                                sortMenuExpanded = false
                                onSortOrderChange(option)
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = if (option == CatalogSortOrder.POPULARITY) {
                                        Icons.AutoMirrored.Filled.TrendingUp
                                    } else Icons.Default.SortByAlpha,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 12.dp).size(20.dp)
                                )
                            }
                        )
                    }
                }
        }
        errorMessage?.let {
            Text(it.resolve(LocalContext.current), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = stringResource(R.string.search_clear)
                        )
                    }
                }
            } else {
                null
            },
            placeholder = { Text(stringResource(R.string.search_catalog_hint)) }
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleLocalItems, key = { "${it.mediaType.toPackId().value}:${it.id}" }) { anime ->
                AnimeListRow(
                    anime,
                    imagesEnabled,
                    oneCoverPerGroup(anime.mediaType),
                    coverRepository,
                    genericCoverRepository,
                    genericGroupCoverRepository,
                    onCoverDownload = { onCoverDownload(anime) },
                    onCoverOpen = { onCoverOpen(anime) },
                    onClick = { onAnime(anime) },
                    onGroupDetails = { onGroupDetails(anime) }
                )
            }
            if (visibleLocalItems.isEmpty()) item {
                val emptyText = if (listLoading) {
                    R.string.loading_list
                } else if (listFailed) {
                    R.string.catalog_unavailable_error
                } else when (catalogAvailability) {
                    CatalogAvailability.LOADING -> R.string.catalog_loading
                    CatalogAvailability.UNAVAILABLE -> R.string.catalog_not_available_yet
                    CatalogAvailability.ERROR -> R.string.catalog_unavailable_error
                    CatalogAvailability.AVAILABLE -> R.string.no_search_results
                }
                Text(stringResource(emptyText), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
            if (loadingMore) item(key = "search-loading-more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
    if (showFilters) {
        if (allSelected || activeMediaType != MediaType.ANIME) {
            GenericMediaFiltersDialog(
                mediaType = activeMediaType,
                current = genericFilters,
                options = genericFilterOptions,
                onDismiss = { showFilters = false },
                onApply = { onGenericFiltersChange(it); showFilters = false }
            )
        } else {
            AnimeFiltersDialog(
                current = filters,
                options = filterOptions,
                onDismiss = { showFilters = false },
                onApply = { onFiltersChange(it); showFilters = false }
            )
        }
    }
}

@Composable
private fun RankingScreen(
    ranked: List<Anime>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    activeMediaType: MediaType,
    availableMediaTypes: Set<MediaType>,
    onMediaTypeSelected: (MediaType) -> Unit,
    allAvailable: Boolean,
    allSelected: Boolean,
    onAllSelected: () -> Unit,
    onUnavailableMediaType: (MediaType) -> Unit,
    imagesEnabled: Boolean,
    oneCoverPerGroup: (MediaType) -> Boolean,
    coverRepository: CoverBitmapCache,
    genericCoverRepository: GenericMediaCoverRepository? = null,
    genericGroupCoverRepository: GenericMediaGroupCoverRepository? = null,
    filters: AnimeFilters,
    genericFilters: GenericMediaFilters,
    mergeSeasonsEnabled: Boolean,
    onMergeSeasonsChange: (Boolean) -> Unit,
    filterOptions: AnimeFilterOptions,
    genericFilterOptions: GenericMediaFilterOptions,
    sortOrder: RankingSortOrder,
    sortDescending: Boolean,
    categoryId: String?,
    categories: List<RatingCategory>,
    onFiltersChange: (AnimeFilters) -> Unit,
    onGenericFiltersChange: (GenericMediaFilters) -> Unit,
    onSortOrderChange: (RankingSortOrder) -> Unit,
    onSortDirectionToggle: () -> Unit,
    onCategorySort: (String) -> Unit,
    onAnime: (Anime) -> Unit,
    onGroupDetails: (Anime) -> Unit,
    onCoverDownload: (Anime) -> Unit,
    onCoverOpen: (Anime) -> Unit,
    catalogAvailability: CatalogAvailability,
    listLoading: Boolean,
    listFailed: Boolean
) {
    var showFilters by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(Bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            MediaScreenTitle(
                title = stringResource(R.string.ranking_title),
                activeMediaType = activeMediaType,
                onMediaTypeSelected = onMediaTypeSelected,
                availableMediaTypes = availableMediaTypes,
                onDownloadPack = { onUnavailableMediaType(activeMediaType) },
                allAvailable = allAvailable,
                allSelected = allSelected,
                onAllSelected = onAllSelected
            )
            Spacer(Modifier.height(10.dp))
            ListActionRow(
                mergeLabel = if (allSelected) null else
                    activeMediaType.mergeLabelResource?.let { stringResource(it) },
                mergeSeasonsEnabled = mergeSeasonsEnabled,
                onMergeSeasonsChange = onMergeSeasonsChange,
                activeFilterCount = if (!allSelected && activeMediaType == MediaType.ANIME) filters.activeCount
                else genericFilters.activeCount,
                sortLabel = rankingSortLabel(sortOrder, categoryId, categories),
                sortReversed = !sortDescending,
                onFilters = { showFilters = true },
                onSort = { sortMenuExpanded = true },
                onSortDirectionToggle = onSortDirectionToggle
            ) {
                    RankOfflinePopupMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                        RankingSortOrder.entries.filterNot { it == RankingSortOrder.CATEGORY_SCORE }.forEach { option ->
                            RankOfflinePopupMenuItem(
                                label = option.localizedLabel(),
                                selected = sortOrder == option,
                                onClick = { sortMenuExpanded = false; onSortOrderChange(option) },
                                leadingContent = {
                                    Icon(
                                        imageVector = when (option) {
                                            RankingSortOrder.FINAL_SCORE -> Icons.Default.Star
                                            RankingSortOrder.QUALITY_SCORE -> Icons.Default.AutoAwesome
                                            RankingSortOrder.PERSONAL_TASTE -> Icons.Default.Favorite
                                            RankingSortOrder.CATEGORY_SCORE -> Icons.Default.Category
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 12.dp).size(20.dp)
                                    )
                                }
                            )
                        }
                        val activeCategories = categories.filter(RatingCategory::active).sortedBy { it.order }
                        if (activeCategories.isNotEmpty()) {
                            HorizontalDivider()
                            Text(
                                stringResource(R.string.sort_category_score),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                            activeCategories.forEach { category ->
                                RankOfflinePopupMenuItem(
                                    label = localizedCategoryName(category),
                                    selected = sortOrder == RankingSortOrder.CATEGORY_SCORE &&
                                        categoryId == category.id,
                                    onClick = { sortMenuExpanded = false; onCategorySort(category.id) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.Category,
                                            contentDescription = null,
                                            tint = ratingCategoryColor(category.color),
                                            modifier = Modifier.padding(end = 12.dp).size(20.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
            }
        }
        if (imagesEnabled) {
            val top3 = ranked.take(3)
            val rest = ranked.drop(3)
            if (top3.isNotEmpty()) {
                item {
                    Top3Podium(
                        top3,
                        sortOrder,
                        oneCoverPerGroup,
                        coverRepository,
                        genericCoverRepository,
                        genericGroupCoverRepository,
                        onCoverDownload,
                        onCoverOpen,
                        onAnime
                    )
                }
            }
            itemsIndexed(rest, key = { _, anime -> "${anime.mediaType.toPackId().value}:${anime.id}" }) { index, anime ->
                AnimeListRow(
                    anime,
                    true,
                    oneCoverPerGroup(anime.mediaType),
                    coverRepository,
                    genericCoverRepository,
                    genericGroupCoverRepository,
                    rank = index + 4,
                    rankingSortOrder = sortOrder,
                    onCoverDownload = { onCoverDownload(anime) },
                    onCoverOpen = { onCoverOpen(anime) },
                    onClick = { onAnime(anime) },
                    onGroupDetails = { onGroupDetails(anime) }
                )
            }
            if (rest.isEmpty() && top3.isEmpty()) {
                item { EmptyRankingMessage(catalogAvailability, listLoading, listFailed) }
            }
        } else {
            itemsIndexed(ranked, key = { _, anime -> "${anime.mediaType.toPackId().value}:${anime.id}" }) { index, anime ->
                AnimeListRow(
                    anime,
                    false,
                    oneCoverPerGroup(anime.mediaType),
                    coverRepository,
                    genericCoverRepository,
                    genericGroupCoverRepository,
                    rank = index + 1,
                    rankingSortOrder = sortOrder,
                    onCoverDownload = { onCoverDownload(anime) },
                    onCoverOpen = { onCoverOpen(anime) },
                    onClick = { onAnime(anime) },
                    onGroupDetails = { onGroupDetails(anime) }
                )
            }
            if (ranked.isEmpty()) {
                item { EmptyRankingMessage(catalogAvailability, listLoading, listFailed) }
            }
        }
    }
    if (showFilters) {
        if (allSelected || activeMediaType != MediaType.ANIME) {
            GenericMediaFiltersDialog(
                mediaType = activeMediaType,
                current = genericFilters,
                options = genericFilterOptions,
                onDismiss = { showFilters = false },
                onApply = { onGenericFiltersChange(it); showFilters = false }
            )
        } else {
            AnimeFiltersDialog(
                current = filters,
                options = filterOptions,
                onDismiss = { showFilters = false },
                onApply = { onFiltersChange(it); showFilters = false }
            )
        }
    }
}

@Composable
private fun ratingCategoryColor(value: String): Color = try {
    Color(android.graphics.Color.parseColor(value))
} catch (_: IllegalArgumentException) {
    MaterialTheme.colorScheme.primary
}

@Composable
private fun ListActionRow(
    mergeLabel: String?,
    mergeSeasonsEnabled: Boolean,
    onMergeSeasonsChange: (Boolean) -> Unit,
    activeFilterCount: Int,
    sortLabel: String,
    sortReversed: Boolean,
    onFilters: () -> Unit,
    onSort: () -> Unit,
    onSortDirectionToggle: () -> Unit,
    sortMenu: @Composable () -> Unit
) {
    val filterLabel = if (activeFilterCount > 0) stringResource(R.string.filters_active, activeFilterCount)
    else stringResource(R.string.filters_title)
    val currentDirection = stringResource(
        if (sortReversed) R.string.sort_direction_ascending else R.string.sort_direction_descending
    )
    val nextDirection = stringResource(
        if (sortReversed) R.string.sort_direction_descending else R.string.sort_direction_ascending
    )
    val sortDirectionDescription = stringResource(
        R.string.sort_direction_accessibility,
        currentDirection,
        nextDirection
    )
    val textStyle = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val buttonWeights = remember(mergeLabel, filterLabel, sortLabel, textStyle, density) {
        val extraWidth = with(density) { 8.dp.toPx() }
        val minimumWidth = with(density) { 40.dp.toPx() }
        val directionWidth = with(density) { 49.dp.toPx() }
        listOfNotNull(mergeLabel, filterLabel, sortLabel).mapIndexed { index, label ->
            maxOf(textMeasurer.measure(label, style = textStyle, maxLines = 1).size.width + extraWidth, minimumWidth)
                .let { if (index == listOfNotNull(mergeLabel, filterLabel, sortLabel).lastIndex) it + directionWidth else it }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        var weightIndex = 0
        if (mergeLabel != null) {
            CompactActionButton(
                label = mergeLabel,
                selected = mergeSeasonsEnabled,
                onClick = { onMergeSeasonsChange(!mergeSeasonsEnabled) },
                modifier = Modifier.weight(buttonWeights[weightIndex++])
            )
        }
        CompactActionButton(
            label = filterLabel,
            selected = activeFilterCount > 0,
            onClick = onFilters,
            modifier = Modifier.weight(buttonWeights[weightIndex++])
        )
        Surface(
            modifier = Modifier.weight(buttonWeights[weightIndex]).height(48.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(onClick = onSort),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        sortLabel,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    sortMenu()
                }
                VerticalDivider(
                    modifier = Modifier.height(28.dp),
                    color = MaterialTheme.colorScheme.outline
                )
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .clickable(onClick = onSortDirectionToggle)
                        .semantics { contentDescription = sortDirectionDescription },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (sortReversed) "↑" else "↓",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactActionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Text(
            label,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun groupFormatLabel(format: String): String = when (format) {
    SERIES_FORMAT_FILTER -> stringResource(R.string.group_format_series)
    MOVIES_FORMAT_FILTER -> stringResource(R.string.group_format_movies)
    SPECIAL_FORMAT_FILTER -> stringResource(R.string.group_format_specials)
    MUSIC_FORMAT_FILTER -> stringResource(R.string.group_format_music)
    else -> formatDisplayName(format)
}

internal fun Anime.localizedDisplayTitle(): String = title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnimeTopAppBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back)
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun GroupDetailScreen(
    detail: GroupDetailUi,
    imagesEnabled: Boolean,
    oneCoverPerGroup: Boolean,
    coverRepository: CoverBitmapCache,
    genericCoverRepository: GenericMediaCoverRepository? = null,
    genericGroupCoverRepository: GenericMediaGroupCoverRepository? = null,
    onBack: () -> Unit,
    onRate: (Boolean) -> Unit,
    onMember: (Anime) -> Unit,
    onCoverDownload: (Anime) -> Unit,
    onCoverOpen: (Anime) -> Unit
) {
    BackHandler(onBack = onBack)
    var showDraftChoice by remember(detail.id) { mutableStateOf(false) }
    val explicit = detail.explicitFinalScore
    val shown = explicit ?: detail.derivedRating.finalScore
    val requestRating = {
        if (explicit == null && detail.derivedRating.ratedCount > 0) showDraftChoice = true
        else onRate(false)
    }
    Scaffold(
        containerColor = Bg,
        topBar = {
            AnimeTopAppBar(detail.displayTitle, onBack)
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            if (explicit != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.group_explicit_rating),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(
                                    R.string.group_format_summary,
                                    groupFormatLabel(detail.type),
                                    detail.members.size
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(
                                    R.string.group_rated_count,
                                    detail.derivedRating.ratedCount,
                                    detail.members.size
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        ScoreValueBadge(explicit, requestRating)
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(
                                    if (shown == null) R.string.group_unrated else R.string.group_calculated
                                ),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(
                                    R.string.group_format_summary,
                                    groupFormatLabel(detail.type),
                                    detail.members.size
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(
                                    R.string.group_rated_count,
                                    detail.derivedRating.ratedCount,
                                    detail.members.size
                                ),
                                color = if (shown == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (shown != null) {
                                ScoreValueBadge(shown, requestRating)
                                Spacer(Modifier.height(8.dp))
                            }
                            OutlinedButton(
                                onClick = requestRating,
                                modifier = Modifier.heightIn(min = 40.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    contentColor = MaterialTheme.colorScheme.primary
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    stringResource(R.string.group_rate_together),
                                    maxLines = 1,
                                    softWrap = false,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.group_members), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(detail.members, key = { it.id }) { member ->
                    AnimeListRow(
                        anime = member,
                        showImages = imagesEnabled,
                        oneCoverPerGroup = oneCoverPerGroup,
                        coverRepository = coverRepository,
                        genericCoverRepository = genericCoverRepository,
                        genericGroupCoverRepository = genericGroupCoverRepository,
                        onCoverDownload = { onCoverDownload(member) },
                        onCoverOpen = { onCoverOpen(member) },
                        onClick = { onMember(member) },
                        onGroupDetails = {}
                    )
                }
            }
        }
    }
    if (showDraftChoice) AlertDialog(
        onDismissRequest = { showDraftChoice = false },
        title = { Text(stringResource(R.string.group_rate_together)) },
        text = { Text(stringResource(R.string.group_draft_choice)) },
        confirmButton = { TextButton(onClick = { showDraftChoice = false; onRate(true) }) { Text(stringResource(R.string.group_use_member_ratings)) } },
        dismissButton = { TextButton(onClick = { showDraftChoice = false; onRate(false) }) { Text(stringResource(R.string.group_start_fresh)) } }
    )
}

@Composable
private fun EmptyRankingMessage(
    availability: CatalogAvailability,
    listLoading: Boolean,
    listFailed: Boolean
) {
    val resource = if (listLoading) R.string.loading_list else if (listFailed) {
        R.string.catalog_unavailable_error
    } else when (availability) {
        CatalogAvailability.LOADING -> R.string.catalog_loading
        CatalogAvailability.UNAVAILABLE -> R.string.catalog_not_available_yet
        CatalogAvailability.ERROR -> R.string.catalog_unavailable_error
        CatalogAvailability.AVAILABLE -> R.string.no_saved_ratings
    }
    Text(stringResource(resource), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
}

@Composable
private fun GenericMediaFiltersDialog(
    mediaType: MediaType,
    current: GenericMediaFilters,
    options: GenericMediaFilterOptions,
    onDismiss: () -> Unit,
    onApply: (GenericMediaFilters) -> Unit
) {
    var draft by remember(current) { mutableStateOf(current) }
    val genreLabels = if (mediaType == MediaType.BOOK) bookGenreLabels() else emptyMap()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filters_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChoice(
                        mediaType.genreFilterLabelResource,
                        draft.genre,
                        options.genres,
                        { genreLabels[it] ?: it }
                    ) {
                        draft = draft.copy(genre = it)
                    }
                }
                item {
                    FilterChoice(
                        R.string.filter_year,
                        draft.year?.toString(),
                        options.years.map(Int::toString)
                    ) { draft = draft.copy(year = it?.toIntOrNull()) }
                }
                mediaType.creditFilterLabelResource?.let { label ->
                    item {
                        FilterChoice(label, draft.creditName, options.creditNames) {
                            draft = draft.copy(creditName = it)
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = { draft = GenericMediaFilters() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.filters_clear)) }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(onClick = { onApply(draft) }) { Text(stringResource(R.string.action_apply)) }
        }
    )
}

@Composable
private fun bookGenreLabels(): Map<String, String> = mapOf(
    "Fantasy" to stringResource(R.string.book_genre_fantasy),
    "Science Fiction" to stringResource(R.string.book_genre_science_fiction),
    "Romance" to stringResource(R.string.book_genre_romance),
    "Mystery & Thriller" to stringResource(R.string.book_genre_mystery_thriller),
    "Horror" to stringResource(R.string.book_genre_horror),
    "Adventure" to stringResource(R.string.book_genre_adventure),
    "Historical Fiction" to stringResource(R.string.book_genre_historical_fiction),
    "Classics" to stringResource(R.string.book_genre_classics),
    "Biography" to stringResource(R.string.book_genre_biography),
    "History" to stringResource(R.string.book_genre_history),
    "Science & Technology" to stringResource(R.string.book_genre_science_technology),
    "Philosophy" to stringResource(R.string.book_genre_philosophy),
    "Other / Unclassified" to stringResource(R.string.book_genre_other)
)

@Composable
private fun AnimeFiltersDialog(
    current: AnimeFilters,
    options: AnimeFilterOptions,
    onDismiss: () -> Unit,
    onApply: (AnimeFilters) -> Unit
) {
    var draft by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filters_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { FilterChoice(R.string.filter_genre, draft.genre, options.genres) { draft = draft.copy(genre = it) } }
                item {
                    val labels = mapOf(
                        SERIES_FORMAT_FILTER to stringResource(R.string.group_format_series),
                        MOVIES_FORMAT_FILTER to stringResource(R.string.group_format_movies),
                        SPECIAL_FORMAT_FILTER to stringResource(R.string.group_format_specials),
                        MUSIC_FORMAT_FILTER to stringResource(R.string.group_format_music)
                    )
                    FilterChoice(
                        R.string.filter_format, draft.format, options.formats,
                        { labels[it] ?: formatDisplayName(it) }
                    ) { draft = draft.copy(format = it) }
                }
                item { FilterChoice(R.string.filter_year, draft.year?.toString(), options.years.map(Int::toString)) { draft = draft.copy(year = it?.toIntOrNull()) } }
                item { FilterChoice(R.string.filter_studio, draft.studio, options.studios) { draft = draft.copy(studio = it) } }
                item {
                    TextButton(onClick = { draft = AnimeFilters() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.filters_clear))
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { TextButton(onClick = { onApply(draft) }) { Text(stringResource(R.string.action_apply)) } }
    )
}

@Composable
private fun FilterChoice(
    @androidx.annotation.StringRes labelResource: Int,
    selected: String?,
    options: List<String>,
    displayValue: (String) -> String = { it },
    onSelected: (String?) -> Unit
) {
    var choosing by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(stringResource(labelResource), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Text(selected?.let(displayValue) ?: stringResource(R.string.filters_all), maxLines = 1)
        }
        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
    }
    if (choosing) {
        FilterValueDialog(
            title = stringResource(labelResource),
            options = options,
            displayValue = displayValue,
            onDismiss = { choosing = false },
            onSelected = {
                choosing = false
                onSelected(it)
            }
        )
    }
}

@Composable
private fun FilterValueDialog(
    title: String,
    options: List<String>,
    displayValue: (String) -> String,
    onDismiss: () -> Unit,
    onSelected: (String?) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var query by remember(title) { mutableStateOf("") }
    val visibleOptions = remember(query, options) {
        if (query.isBlank()) options else options.filter {
            it.contains(query.trim(), ignoreCase = true) ||
                displayValue(it).contains(query.trim(), ignoreCase = true)
        }
    }
    AlertDialog(
        modifier = Modifier.dismissFocusOnUnconsumedTap(focusManager),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.filter_search_values)) }
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    item {
                        TextButton(onClick = { onSelected(null) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.filters_all))
                        }
                    }
                    items(visibleOptions, key = { it }) { option ->
                        TextButton(onClick = { onSelected(option) }, modifier = Modifier.fillMaxWidth()) {
                            Text(displayValue(option), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

internal fun formatDisplayName(raw: String): String = raw.split('_').joinToString(" ") { part ->
    when (val upper = part.uppercase(Locale.ROOT)) {
        "TV", "OVA", "ONA" -> upper
        else -> part.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercaseChar)
    }
}

@Composable
internal fun Anime.metadataSubtitle(): String {
    val item = toCatalogItemUiModel()
    val normalizedFormat = normalizedAnimeFormat(item.format)?.let { groupFormatLabel(it) }
    return joinMetadataSubtitle(
        filterMetadataSubtitleSegments(
            mediaType = item.mediaType,
            genre = item.genre,
            format = normalizedFormat,
            year = item.year,
            credit = item.filterCredit
        )
    )
}

@Composable
private fun AnimeListRow(
    anime: Anime,
    showImages: Boolean,
    oneCoverPerGroup: Boolean,
    coverRepository: CoverBitmapCache,
    genericCoverRepository: GenericMediaCoverRepository? = null,
    genericGroupCoverRepository: GenericMediaGroupCoverRepository? = null,
    rank: Int? = null,
    rankingSortOrder: RankingSortOrder? = null,
    onCoverDownload: () -> Unit,
    onCoverOpen: () -> Unit,
    onClick: () -> Unit,
    onGroupDetails: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable {
            if (animeListRowAction(anime, memberCountClicked = false) == AnimeListRowAction.RATE) onClick()
        },
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (rank != null) {
                Box(
                    modifier = Modifier.width(36.dp).heightIn(min = 52.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text("${rank}.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
            }
            if (showImages) {
                val coverAnime = anime.coverTarget(oneCoverPerGroup)
                AnimeCover(
                    anime = coverAnime,
                    coverRepository = coverRepository,
                    genericCoverRepository = genericCoverRepository,
                    genericGroupCoverRepository = genericGroupCoverRepository,
                    onDownloadRequested = onCoverDownload,
                    onCoverClick = onCoverOpen,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
                    allowDownload = coverAnime.groupId == null || coverAnime.supportsRemoteGroupCover()
                )
                Spacer(Modifier.width(12.dp))
            }
            Row(
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(anime.localizedDisplayTitle(), maxLines = 2, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    val subtitle = if (anime.groupId != null) {
                        stringResource(R.string.group_entries_count, anime.memberCount)
                    } else {
                        anime.metadataSubtitle()
                    }
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle,
                            color = if (anime.groupId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                            modifier = if (anime.groupId != null) {
                                Modifier.clickable {
                                    if (animeListRowAction(anime, memberCountClicked = true) == AnimeListRowAction.GROUP_DETAILS) {
                                        onGroupDetails()
                                    }
                                }
                            } else {
                                Modifier
                            }
                        )
                    }
                }
                val shownScore = if (rankingSortOrder == null) anime.finalScore else anime.rankingDisplayScore(rankingSortOrder)
                if (shownScore != null || rankingSortOrder != null) {
                    val scoreBackground = shownScore?.let(::scoreColor)
                        ?: MaterialTheme.colorScheme.surfaceVariant
                    Surface(color = scoreBackground, shape = RoundedCornerShape(12.dp)) {
                        Text(
                            shownScore?.let { "%.2f".format(it) } ?: "—",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            fontWeight = FontWeight.Bold,
                            color = bestContentColor(scoreBackground)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RatingScreen(
    anime: Anime,
    groupScore: Boolean,
    initial: RatingDraft,
    settings: AppSettingsState,
    onBack: () -> Unit,
    onSaveElaborate: (RatingDraft, Double?, String) -> Unit,
    onSaveSimple: (RatingDraft, Double, Double) -> Unit,
    onDelete: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val config = settings.ratingConfig
    val elaborate = settings.elaborateRatingEnabled
    val profiles = settings.ratingProfiles.profiles
    val initialProfile = remember(initial.profileId, profiles) {
        settings.ratingProfiles.resolveProfile(initial.profileId)
    }
    val editorIdentity = remember(anime.mediaType, anime.id, groupScore) {
        "${anime.mediaType.toPackId().value}:${anime.id}:$groupScore"
    }
    var selectedProfileId by remember(editorIdentity, initialProfile.id) { mutableStateOf(initialProfile.id) }
    val selectedProfile = settings.ratingProfiles.resolveProfile(selectedProfileId)
    LaunchedEffect(selectedProfile.id) {
        if (selectedProfileId != selectedProfile.id) selectedProfileId = selectedProfile.id
    }
    val calculationConfig = config.withProfile(selectedProfile)
    val defaultDraft = remember(editorIdentity, initial, config.categories, elaborate) {
        if (elaborate && initial.categoryScores.isEmpty()) initial.withDefaultCategoryScores(config) else initial
    }
    var draft by remember(editorIdentity, defaultDraft) { mutableStateOf(defaultDraft) }
    val initialSimpleQuality = remember(editorIdentity, initial.qualityScore, initial.finalScore, initial.additiveScore) {
        val candidate = initial.qualityScore
            ?: initial.finalScore?.minus(initial.additiveScore)
            ?: 5.0
        snapRatingValue(candidate.coerceIn(RATING_MIN, RATING_MAX)) ?: 5.0
    }
    var simpleQuality by remember(editorIdentity, initialSimpleQuality) { mutableDoubleStateOf(initialSimpleQuality) }
    var directFinalScore by remember(editorIdentity, elaborate, initial.finalScore, defaultDraft) {
        val calculated = if (elaborate) {
            defaultDraft.calculatedFinalScore(calculationConfig)
        } else {
            finalScoreFromQuality(initialSimpleQuality, defaultDraft.additiveScore)
        }
        val savedScore = initial.finalScore
        val differs = savedScore != null &&
            (calculated == null || kotlin.math.abs(savedScore - calculated) > 1e-9)
        mutableStateOf(savedScore.takeIf { differs })
    }
    var showDirectScoreDialog by remember(editorIdentity) { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val base = if (elaborate) draft.baseScore(calculationConfig) else simpleQuality
    val calculatedFinal = directFinalScore ?: if (elaborate) {
        draft.calculatedFinalScore(calculationConfig)
    } else {
        finalScoreFromQuality(simpleQuality, draft.additiveScore)
    }
    val stickyScore = settings.stickyScore

    Scaffold(
        modifier = Modifier.dismissFocusOnUnconsumedTap(focusManager),
        containerColor = Bg,
        topBar = {
            AnimeTopAppBar(anime.localizedDisplayTitle(), onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 36.dp)
        ) {
            if (stickyScore) {
                stickyHeader {
                    ScoreHeader(base, draft.additiveScore, calculatedFinal, config, groupScore) {
                        showDirectScoreDialog = true
                    }
                }
            } else {
                item {
                    ScoreHeader(base, draft.additiveScore, calculatedFinal, config, groupScore) {
                        showDirectScoreDialog = true
                    }
                }
            }
            if (elaborate) {
                item {
                    RatingProfileSelector(
                        profiles = profiles,
                        selected = selectedProfile,
                        onSelected = {
                            selectedProfileId = it.id
                        }
                    )
                }
            }
            if (elaborate) {
                items(calculationConfig.categories.filter { it.active }.sortedBy { it.order }, key = { it.id }) { category ->
                    CategoryEditor(category, draft.categoryScores[category.id]) { newValue ->
                        draft = draft.copy(categoryScores = draft.categoryScores + (category.id to newValue))
                        directFinalScore = null
                    }
                }
            } else {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.rating_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.rating_simple_instructions),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                            )
                            Text(
                                stringResource(R.string.score_out_of_ten, simpleQuality),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            RatingSlider(
                                value = simpleQuality,
                                onValueChange = {
                                    simpleQuality = it
                                    directFinalScore = null
                                },
                                accessibilityLabel = stringResource(R.string.rating_slider_accessibility)
                            )
                        }
                    }
                }
            }
            config.additiveBonus?.let { bonus ->
                item {
                    BonusEditor(
                        bonus = bonus,
                        value = draft.additiveScore,
                        onValueChange = {
                            draft = draft.copy(additiveScore = it)
                            directFinalScore = null
                        }
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = draft.notes,
                    onValueChange = { draft = draft.copy(notes = it) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = { Text(stringResource(R.string.rating_notes)) },
                    placeholder = { Text(stringResource(R.string.rating_notes_hint)) }
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (elaborate) {
                                onSaveElaborate(draft, directFinalScore, selectedProfile.id)
                            } else {
                                calculatedFinal?.let { onSaveSimple(draft, simpleQuality, it) }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_save))
                    }
                    if (anime.finalScore != null) {
                        OutlinedButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
    }
    if (showDirectScoreDialog) {
        DirectScoreDialog(
            initialValue = calculatedFinal,
            onDismiss = { showDirectScoreDialog = false },
            onApply = {
                directFinalScore = it
                showDirectScoreDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RatingProfileSelector(
    profiles: List<RatingProfile>,
    selected: RatingProfile,
    onSelected: (RatingProfile) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.testTag("rating_profile_selector")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.rating_profile),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Text(
                    localizedProfileName(selected),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = stringResource(R.string.rating_profile),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            LazyColumn(
                modifier = Modifier
                    .testTag("rating_profile_list")
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.rating_profile),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
                items(profiles, key = RatingProfile::id) { profile ->
                    val isSelected = profile.id == selected.id
                    Surface(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 3.dp)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else Color.Transparent,
                        border = if (isSelected) BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        ) else null
                    ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rating_profile_${profile.id}")
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = {
                                    expanded = false
                                    if (!isSelected) onSelected(profile)
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            localizedProfileName(profile),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreHeader(
    base: Double?,
    additive: Double,
    final: Double?,
    config: RatingSystemConfig,
    groupScore: Boolean = false,
    onFinalScoreClick: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(
                stringResource(if (groupScore) R.string.score_group_title else R.string.score_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        stringResource(R.string.score_quality, base?.let { "%.2f".format(it) } ?: "—"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (config.additiveBonus != null) {
                        Text(
                            stringResource(
                                R.string.score_bonus_value,
                                localizedBonusName(config.additiveBonus),
                                additive
                            ),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                ScoreValueBadge(final, onFinalScoreClick)
            }
        }
    }
}

@Composable
private fun ScoreValueBadge(score: Double?, onClick: (() -> Unit)? = null) {
    val badgeColor = scoreColor(score ?: 0.0)
    Surface(
        modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
        color = badgeColor,
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            score?.let { "%.2f".format(it) } ?: "—",
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            color = bestContentColor(badgeColor),
            fontWeight = FontWeight.Black,
            fontSize = 24.sp
        )
    }
}

@Composable
private fun BonusEditor(
    bonus: AdditiveBonus,
    value: Double,
    onValueChange: (Double) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.bonus_range, localizedBonusName(bonus), bonus.maxValue),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(stringResource(R.string.bonus_explanation), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            DiscreteSlider(
                value = value,
                onValueChange = onValueChange,
                min = BONUS_MIN,
                max = BONUS_MAX,
                step = BONUS_STEP,
                accessibilityLabel = localizedBonusName(bonus),
                valueDescription = stringResource(R.string.bonus_current, value)
            )
            Text(stringResource(R.string.bonus_current, value), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DirectScoreDialog(
    initialValue: Double?,
    onDismiss: () -> Unit,
    onApply: (Double) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var input by remember(initialValue) {
        mutableStateOf(initialValue?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty())
    }
    val parsed = parseDirectRatingValue(input)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        modifier = Modifier.dismissFocusOnUnconsumedTap(focusManager),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.score_direct_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.score_direct_explanation), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    label = { Text(stringResource(R.string.score_direct_label)) },
                    supportingText = {
                        if (input.isNotBlank() && parsed == null) {
                            Text(stringResource(R.string.score_direct_invalid))
                        }
                    },
                    isError = input.isNotBlank() && parsed == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { parsed?.let(onApply) })
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let(onApply) }) {
                Text(stringResource(R.string.action_apply))
            }
        }
    )
}

@Composable
private fun CategoryEditor(category: RatingCategory, score: Double?, onChange: (Double?) -> Unit) {
    val accent = try {
        Color(android.graphics.Color.parseColor(category.color))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.score_category_heading, localizedCategoryName(category), category.weight.toInt()),
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onChange(if (score == null) 5.0 else null) }) {
                    Text(stringResource(if (score == null) R.string.score_activate_category else R.string.score_not_applicable))
                }
            }
            Text(localizedCategoryDescription(category), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            if (score != null) {
                RatingSlider(
                    value = score,
                    onValueChange = onChange,
                    accessibilityLabel = stringResource(
                        R.string.rating_category_slider_accessibility,
                        localizedCategoryName(category)
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(scoreLabel(score), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Text(stringResource(R.string.score_out_of_ten, score), fontWeight = FontWeight.Bold)
                }
            } else {
                Text(
                    stringResource(R.string.score_not_applicable_explanation),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun RatingSlider(
    value: Double,
    onValueChange: (Double) -> Unit,
    accessibilityLabel: String,
    modifier: Modifier = Modifier
) {
    val normalizedValue = snapRatingValue(value) ?: RATING_MIN
    val valueDescription = stringResource(R.string.score_out_of_ten, normalizedValue)
    DiscreteSlider(
        value = normalizedValue,
        onValueChange = onValueChange,
        min = RATING_MIN,
        max = RATING_MAX,
        step = RATING_STEP,
        accessibilityLabel = accessibilityLabel,
        valueDescription = valueDescription,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscreteSlider(
    value: Double,
    onValueChange: (Double) -> Unit,
    min: Double,
    max: Double,
    step: Double,
    accessibilityLabel: String,
    valueDescription: String,
    modifier: Modifier = Modifier
) {
    val normalizedValue = snapDiscreteValue(value, min, max, step) ?: value.coerceIn(min, max)
    val allValues = remember(min, max, step) { discreteValues(min, max, step) }
    val tickFractions = remember(allValues) {
        allValues.drop(1).dropLast(1).map { ((it - min) / (max - min)).toFloat() }
    }
    val activeFraction = ((normalizedValue - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    val activeColor = MaterialTheme.colorScheme.primary
    val trackCutoutColor = MaterialTheme.colorScheme.surface
    val inactiveColor = sliderInactiveTrackColor(
        background = MaterialTheme.colorScheme.background,
        surface = MaterialTheme.colorScheme.surface,
        onSurface = MaterialTheme.colorScheme.onSurface
    )
    Slider(
        value = normalizedValue.toFloat(),
        onValueChange = { rawValue ->
            snapDiscreteValue(rawValue.toDouble(), min, max, step)?.let(onValueChange)
        },
        valueRange = min.toFloat()..max.toFloat(),
        steps = allValues.size - 2,
        track = {
            Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                val centerY = size.height / 2f
                val strokeWidth = 16.dp.toPx()
                drawLine(
                    color = inactiveColor,
                    start = Offset(0f, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = activeColor,
                    start = Offset(0f, centerY),
                    end = Offset(size.width * activeFraction, centerY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                val thumbCenterX = size.width * activeFraction
                drawLine(
                    color = trackCutoutColor,
                    start = Offset(thumbCenterX - 8.dp.toPx(), centerY),
                    end = Offset(thumbCenterX + 8.dp.toPx(), centerY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Butt
                )
                tickFractions.forEach { fraction ->
                    drawCircle(
                        color = if (fraction <= activeFraction) trackCutoutColor else activeColor,
                        radius = 2.dp.toPx(),
                        center = Offset(size.width * fraction, centerY)
                    )
                }
            }
        },
        modifier = modifier.semantics {
            contentDescription = accessibilityLabel
            stateDescription = valueDescription
        }
    )
}

internal fun sliderInactiveTrackColor(
    background: Color,
    surface: Color,
    onSurface: Color
): Color = lerp(
    start = surface,
    stop = onSurface,
    fraction = if (background.luminance() >= 0.5f) 0.12f else 0.24f
)

@Composable
private fun Top3Podium(
    top3: List<Anime>,
    sortOrder: RankingSortOrder,
    oneCoverPerGroup: (MediaType) -> Boolean,
    coverRepository: CoverBitmapCache,
    genericCoverRepository: GenericMediaCoverRepository? = null,
    genericGroupCoverRepository: GenericMediaGroupCoverRepository? = null,
    onCoverDownload: (Anime) -> Unit,
    onCoverOpen: (Anime) -> Unit,
    onAnime: (Anime) -> Unit
) {
    val displayedPlaces = when (top3.size) {
        1 -> listOf(0 to 1)
        2 -> listOf(1 to 2, 0 to 1)
        else -> listOf(1 to 2, 0 to 1, 2 to 3)
    }
    val gap = 6.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // All three artworks share this one dp-per-source-pixel scale, even when
        // fewer than three ranked entries are currently available.
        val globalScale = ((maxWidth - gap * 2).coerceAtLeast(0.dp).value /
            PODIUM_TOTAL_SOURCE_WIDTH).coerceAtLeast(0f)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom
        ) {
            displayedPlaces.forEach { (animeIndex, position) ->
                val anime = top3[animeIndex]
                PodiumItem(
                    anime = anime,
                    position = position,
                    artwork = podiumArtwork(position),
                    globalScale = globalScale,
                    sortOrder = sortOrder,
                    oneCoverPerGroup = oneCoverPerGroup(anime.mediaType),
                    coverRepository = coverRepository,
                    genericCoverRepository = genericCoverRepository,
                    genericGroupCoverRepository = genericGroupCoverRepository,
                    onCoverDownload = { onCoverDownload(anime) },
                    onCoverOpen = { onCoverOpen(anime) },
                    onOpenRating = { onAnime(anime) }
                )
            }
        }
    }
}

private data class PodiumArtwork(val drawable: Int, val sourceWidth: Float, val sourceHeight: Float)

private const val PODIUM_TOTAL_SOURCE_WIDTH = 487f + 525f + 483f
private val PODIUM_COVER_OVERLAP = 8.dp

private fun podiumArtwork(position: Int): PodiumArtwork = when (position) {
    1 -> PodiumArtwork(R.drawable.rankoffline_podium_1, 525f, 400f)
    2 -> PodiumArtwork(R.drawable.rankoffline_podium_2, 487f, 341f)
    else -> PodiumArtwork(R.drawable.rankoffline_podium_3, 483f, 310f)
}

@Composable
private fun PodiumItem(
    anime: Anime,
    position: Int,
    artwork: PodiumArtwork,
    globalScale: Float,
    sortOrder: RankingSortOrder,
    oneCoverPerGroup: Boolean,
    coverRepository: CoverBitmapCache,
    genericCoverRepository: GenericMediaCoverRepository? = null,
    genericGroupCoverRepository: GenericMediaGroupCoverRepository? = null,
    onCoverDownload: () -> Unit,
    onCoverOpen: () -> Unit,
    onOpenRating: () -> Unit
) {
    val isFirst = position == 1
    val podiumWidth = (artwork.sourceWidth * globalScale).dp
    val podiumHeight = (artwork.sourceHeight * globalScale).dp
    val coverSize = minOf(if (isFirst) 120.dp else 96.dp, podiumWidth)
    val shownScore = anime.rankingDisplayScore(sortOrder)
    val scoreColorValue = shownScore?.let(::scoreColor)
        ?: MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier.width(podiumWidth),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            color = scoreColorValue,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.clickable { dispatchTop3ScoreTap(onOpenRating) }
        ) {
            Text(
                shownScore?.let { "%.2f".format(it) } ?: "—",
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                color = bestContentColor(scoreColorValue),
                fontWeight = FontWeight.Bold,
                fontSize = if (isFirst) 18.sp else 16.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .width(podiumWidth)
                .height(coverSize + podiumHeight - PODIUM_COVER_OVERLAP)
        ) {
            AnimeCover(
                anime = anime.coverTarget(oneCoverPerGroup),
                coverRepository = coverRepository,
                genericCoverRepository = genericCoverRepository,
                genericGroupCoverRepository = genericGroupCoverRepository,
                onDownloadRequested = onCoverDownload,
                onCoverClick = onCoverOpen,
                modifier = Modifier
                    .size(coverSize)
                    .align(Alignment.TopCenter)
                    .clip(RoundedCornerShape(12.dp))
                    .zIndex(1f)
            )
            Image(
                painter = painterResource(id = artwork.drawable),
                contentDescription = null,
                modifier = Modifier
                    .size(podiumWidth, podiumHeight)
                    .align(Alignment.BottomCenter),
                contentScale = ContentScale.Fit
            )
        }
    }
}
