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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
import kotlinx.coroutines.flow.flowOf
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

private val Bg = Color(0xFF0A0B12)
private val Panel = Color(0xFF121522)
private val Cyan = Color(0xFF19D5E5)
private val Magenta = Color(0xFFE04BCF)
private val Coral = Color(0xFFFF675D)
private val Orange = Color(0xFFFFAA55)
private val Mint = Color(0xFF62D6C8)
private val CategoryColorPalette = listOf(
    "#EF5350", "#FF675D", "#FF8A65", "#FFAA55", "#FFD54F",
    "#D4E157", "#81C784", "#4DB6AC", "#62D6C8", "#19D5E5",
    "#4FC3F7", "#7EC7FF", "#5C6BC0", "#7986CB", "#9575CD",
    "#BA68C8", "#E04BCF", "#F06292", "#EC407A", "#B0BEC5"
)

class MainActivity : AppCompatActivity() {
    private val appViewModel: RankOfflineViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Cyan,
                    secondary = Magenta,
                    background = Bg,
                    surface = Panel
                )
            ) {
                RankOfflineApp(appViewModel)
            }
        }
    }
}

@Composable
fun RankOfflineApp(viewModel: RankOfflineViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var coverViewerAnime by remember { mutableStateOf<Anime?>(null) }
    val settings by viewModel.settings.collectAsState()
    val ready by viewModel.ready.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val ratingEditor by viewModel.ratingEditor.collectAsState()
    val groupDetail by viewModel.groupDetail.collectAsState()
    val list by viewModel.searchResults.collectAsState()
    val ranked by viewModel.ranking.collectAsState()
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
    val genericFilterOptions = genericFilterOptionsByMedia[settings.activeMediaType]
        ?: GenericMediaFilterOptions()
    val catalogAvailability = catalogAvailabilityByMedia[settings.activeMediaType]
        ?: CatalogAvailability.UNAVAILABLE
    val catalogCount by viewModel.catalogCount.collectAsState()
    val catalogListStates = remember { MediaType.entries.associateWith { LazyListState() } }
    val rankingListStates = remember { MediaType.entries.associateWith { LazyListState() } }
    val catalogListState = catalogListStates.getValue(settings.activeMediaType)
    val rankingListState = rankingListStates.getValue(settings.activeMediaType)
    val previousCatalogStates = remember { mutableStateMapOf<MediaType, SearchUiState>() }
    var pendingCatalogTopReset by remember { mutableStateOf<MediaType?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

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
            CircularProgressIndicator(color = Cyan)
        }
        return
    }

    coverViewerAnime?.let { anime ->
        CoverViewerDialog(
            anime = anime,
            coverRepository = viewModel.coverRepository,
            onDismiss = { coverViewerAnime = null },
            onDeleteConfirmed = { onResult ->
                viewModel.deleteDownloadedCover(anime) { deleted ->
                    if (deleted) coverViewerAnime = null
                    onResult(deleted)
                }
            }
        )
    }

    ratingEditor?.let { editor ->
        if (editor.draft == null) {
            Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Cyan)
            }
        } else {
            RatingScreen(
                anime = editor.anime,
                initial = editor.draft,
                settings = settings,
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
        GroupDetailScreen(
            detail = detail,
            imagesEnabled = settings.showImages,
            coverRepository = viewModel.coverRepository,
            onBack = viewModel::closeGroup,
            onRate = viewModel::rateGroup,
            onMember = viewModel::openRating,
            onCoverDownload = viewModel::downloadCover,
            onCoverOpen = { coverViewerAnime = it }
        )
        return
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Search, null) }, label = { Text(stringResource(R.string.nav_anime)) })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.EmojiEvents, null) }, label = { Text(stringResource(R.string.nav_ranking)) })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text(stringResource(R.string.nav_settings)) })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> AnimeListScreen(
                    list = list,
                    listState = catalogListState,
                    activeMediaType = settings.activeMediaType,
                    onMediaTypeSelected = viewModel::updateActiveMediaType,
                    query = query,
                    imagesEnabled = settings.showImages,
                    coverRepository = viewModel.coverRepository,
                    onQueryChange = viewModel::setSearchQuery,
                    sortOrder = catalogSortOrder,
                    onSortOrderChange = viewModel::setCatalogSortCriterion,
                    onSortDirectionToggle = viewModel::toggleCatalogSortDirection,
                    filters = catalogFilters,
                    genericFilters = searchState.genericFilters,
                    mergeSeasonsEnabled = settings.mergeSeasonsEnabled,
                    onMergeSeasonsChange = viewModel::updateMergeSeasonsEnabled,
                    filterOptions = filterOptions,
                    genericFilterOptions = genericFilterOptions,
                    onFiltersChange = viewModel::setCatalogFilters,
                    onGenericFiltersChange = viewModel::setGenericCatalogFilters,
                    onAnime = viewModel::openAnime,
                    onGroupDetails = viewModel::openGroupDetails,
                    onCoverDownload = { viewModel.downloadCover(it.coverTarget()) },
                    onCoverOpen = { coverViewerAnime = it.coverTarget() },
                    errorMessage = errorMessage,
                    catalogAvailability = catalogAvailability
                )
                1 -> RankingScreen(
                    ranked = ranked,
                    listState = rankingListState,
                    activeMediaType = settings.activeMediaType,
                    onMediaTypeSelected = viewModel::updateActiveMediaType,
                    imagesEnabled = settings.showImages,
                    coverRepository = viewModel.coverRepository,
                    filters = rankingFilters,
                    genericFilters = rankingState.genericFilters,
                    mergeSeasonsEnabled = settings.mergeSeasonsEnabled,
                    onMergeSeasonsChange = viewModel::updateMergeSeasonsEnabled,
                    filterOptions = filterOptions,
                    genericFilterOptions = genericFilterOptions,
                    sortOrder = rankingSortOrder,
                    sortDescending = rankingSortDescending,
                    categoryId = rankingCategoryId,
                    categories = settings.ratingConfig.categories,
                    onFiltersChange = viewModel::setRankingFilters,
                    onGenericFiltersChange = viewModel::setGenericRankingFilters,
                    onSortOrderChange = viewModel::setRankingSortOrder,
                    onSortDirectionToggle = viewModel::toggleRankingSortDirection,
                    onCategorySort = viewModel::setRankingCategory,
                    onAnime = viewModel::openAnime,
                    onGroupDetails = viewModel::openGroupDetails,
                    onCoverDownload = { viewModel.downloadCover(it.coverTarget()) },
                    onCoverOpen = { coverViewerAnime = it.coverTarget() },
                    catalogAvailability = catalogAvailability
                )
                else -> SettingsScreen(settings, catalogCount, viewModel)
            }
        }
    }
}

@Composable
private fun AnimeListScreen(
    list: List<Anime>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    activeMediaType: MediaType,
    onMediaTypeSelected: (MediaType) -> Unit,
    query: String,
    imagesEnabled: Boolean,
    coverRepository: CoverRepository,
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
    catalogAvailability: CatalogAvailability
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        MediaScreenTitle(
            title = stringResource(R.string.app_name),
            activeMediaType = activeMediaType,
            onMediaTypeSelected = onMediaTypeSelected
        )
        Spacer(Modifier.height(10.dp))
        ListActionRow(
            mergeLabel = activeMediaType.mergeLabelResource?.let { stringResource(it) },
            mergeSeasonsEnabled = mergeSeasonsEnabled,
            onMergeSeasonsChange = onMergeSeasonsChange,
            activeFilterCount = if (activeMediaType.dataPath() == MediaDataPath.GENERIC) {
                genericFilters.activeCount
            } else filters.activeCount,
            sortLabel = sortOrder.criterionLabel(),
            sortReversed = sortOrder.isReversed,
            onFilters = { showFilters = true },
            onSort = { sortMenuExpanded = true },
            onSortDirectionToggle = onSortDirectionToggle
        ) {
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    activeMediaType.catalogSortCriteria().forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.criterionLabel()) },
                            onClick = {
                                sortMenuExpanded = false
                                onSortOrderChange(option)
                            }
                        )
                    }
                }
        }
        errorMessage?.let { Text(it.resolve(LocalContext.current), color = Coral, fontSize = 13.sp) }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text(stringResource(R.string.search_catalog_hint)) }
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { anime ->
                AnimeListRow(
                    anime,
                    imagesEnabled,
                    coverRepository,
                    onCoverDownload = { onCoverDownload(anime) },
                    onCoverOpen = { onCoverOpen(anime) },
                    onClick = { onAnime(anime) },
                    onGroupDetails = { onGroupDetails(anime) }
                )
            }
            if (list.isEmpty()) item {
                val emptyText = when (catalogAvailability) {
                    CatalogAvailability.LOADING -> R.string.catalog_loading
                    CatalogAvailability.UNAVAILABLE -> R.string.catalog_not_available_yet
                    CatalogAvailability.ERROR -> R.string.catalog_unavailable_error
                    CatalogAvailability.AVAILABLE -> R.string.no_search_results
                }
                Text(stringResource(emptyText), color = Color.Gray, modifier = Modifier.padding(20.dp))
            }
        }
    }
    if (showFilters) {
        if (activeMediaType.dataPath() == MediaDataPath.GENERIC) {
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
    onMediaTypeSelected: (MediaType) -> Unit,
    imagesEnabled: Boolean,
    coverRepository: CoverRepository,
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
    catalogAvailability: CatalogAvailability
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
                onMediaTypeSelected = onMediaTypeSelected
            )
            Spacer(Modifier.height(10.dp))
            ListActionRow(
                mergeLabel = activeMediaType.mergeLabelResource?.let { stringResource(it) },
                mergeSeasonsEnabled = mergeSeasonsEnabled,
                onMergeSeasonsChange = onMergeSeasonsChange,
                activeFilterCount = if (activeMediaType.dataPath() == MediaDataPath.GENERIC) {
                    genericFilters.activeCount
                } else filters.activeCount,
                sortLabel = rankingSortLabel(sortOrder, categoryId, categories),
                sortReversed = !sortDescending,
                onFilters = { showFilters = true },
                onSort = { sortMenuExpanded = true },
                onSortDirectionToggle = onSortDirectionToggle
            ) {
                    DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                        RankingSortOrder.entries.filterNot { it == RankingSortOrder.CATEGORY_SCORE }.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.localizedLabel()) },
                                onClick = { sortMenuExpanded = false; onSortOrderChange(option) }
                            )
                        }
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.sort_category_score),
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        categories.sortedBy { it.order }.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(localizedCategoryName(category)) },
                                onClick = { sortMenuExpanded = false; onCategorySort(category.id) }
                            )
                        }
                    }
            }
        }
        if (imagesEnabled) {
            val top3 = ranked.take(3)
            val rest = ranked.drop(3)
            if (top3.isNotEmpty()) {
                item { Top3Podium(top3, sortOrder, coverRepository, onCoverDownload, onCoverOpen, onAnime) }
            }
            itemsIndexed(rest, key = { _, anime -> anime.id }) { index, anime ->
                AnimeListRow(
                    anime,
                    true,
                    coverRepository,
                    rank = index + 4,
                    rankingSortOrder = sortOrder,
                    onCoverDownload = { onCoverDownload(anime) },
                    onCoverOpen = { onCoverOpen(anime) },
                    onClick = { onAnime(anime) },
                    onGroupDetails = { onGroupDetails(anime) }
                )
            }
            if (rest.isEmpty() && top3.isEmpty()) {
                item { EmptyRankingMessage(catalogAvailability) }
            }
        } else {
            itemsIndexed(ranked, key = { _, anime -> anime.id }) { index, anime ->
                AnimeListRow(
                    anime,
                    false,
                    coverRepository,
                    rank = index + 1,
                    rankingSortOrder = sortOrder,
                    onCoverDownload = { onCoverDownload(anime) },
                    onCoverOpen = { onCoverOpen(anime) },
                    onClick = { onAnime(anime) },
                    onGroupDetails = { onGroupDetails(anime) }
                )
            }
            if (ranked.isEmpty()) {
                item { EmptyRankingMessage(catalogAvailability) }
            }
        }
    }
    if (showFilters) {
        if (activeMediaType.dataPath() == MediaDataPath.GENERIC) {
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
    val sortDirectionDescription = stringResource(R.string.sort_action)
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
                        .background(Cyan.copy(alpha = 0.08f))
                        .clickable(onClick = onSortDirectionToggle)
                        .semantics { contentDescription = sortDirectionDescription },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (sortReversed) "↑" else "↓",
                        color = Cyan,
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
        border = BorderStroke(1.dp, if (selected) Cyan else MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Cyan.copy(alpha = 0.18f) else Color.Transparent,
            contentColor = if (selected) Cyan else MaterialTheme.colorScheme.onSurface
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

private fun Anime.localizedDisplayTitle(): String = title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnimeTopAppBar(
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
    coverRepository: CoverRepository,
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
                                color = Color.LightGray
                            )
                            Text(
                                stringResource(
                                    R.string.group_rated_count,
                                    detail.derivedRating.ratedCount,
                                    detail.members.size
                                ),
                                color = Color.LightGray
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
                                color = Color.LightGray
                            )
                            Text(
                                stringResource(
                                    R.string.group_rated_count,
                                    detail.derivedRating.ratedCount,
                                    detail.members.size
                                ),
                                color = if (shown == null) Color.LightGray else Mint
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
                                border = BorderStroke(1.dp, Cyan),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = Cyan.copy(alpha = 0.12f),
                                    contentColor = Cyan
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
                        coverRepository = coverRepository,
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
private fun EmptyRankingMessage(availability: CatalogAvailability) {
    val resource = when (availability) {
        CatalogAvailability.LOADING -> R.string.catalog_loading
        CatalogAvailability.UNAVAILABLE -> R.string.catalog_not_available_yet
        CatalogAvailability.ERROR -> R.string.catalog_unavailable_error
        CatalogAvailability.AVAILABLE -> R.string.no_saved_ratings
    }
    Text(stringResource(resource), color = Color.Gray, modifier = Modifier.padding(20.dp))
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filters_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChoice(mediaType.genreFilterLabelResource, draft.genre, options.genres) {
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
                if (mediaType == MediaType.MANGA) {
                    item {
                        FilterChoice(
                            R.string.filter_demographic,
                            draft.demographic,
                            options.demographics
                        ) { draft = draft.copy(demographic = it) }
                    }
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
            Text(stringResource(labelResource), color = Color.Gray, fontSize = 11.sp)
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
    var query by remember(title) { mutableStateOf("") }
    val visibleOptions = remember(query, options) {
        if (query.isBlank()) options else options.filter {
            it.contains(query.trim(), ignoreCase = true) ||
                displayValue(it).contains(query.trim(), ignoreCase = true)
        }
    }
    AlertDialog(
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
private fun Anime.metadataSubtitle(): String {
    val normalizedFormat = normalizedAnimeFormat(format)?.let { groupFormatLabel(it) }
    return joinMetadataSubtitle(metadataSubtitleSegments(normalizedFormat, metadataYear(), studio))
}

@Composable
private fun AnimeListRow(
    anime: Anime,
    showImages: Boolean,
    coverRepository: CoverRepository,
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
                    Text("${rank}.", color = Color.LightGray, fontWeight = FontWeight.Bold)
                }
            }
            if (showImages) {
                AnimeCover(
                    anime.coverTarget(),
                    coverRepository,
                    onCoverDownload,
                    onCoverOpen,
                    Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
                    allowDownload = anime.mediaType == MediaType.ANIME ||
                        anime.groupId == null || anime.supportsRemoteGroupCover()
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
                            color = if (anime.groupId != null) Cyan else Color.LightGray,
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
                    Surface(color = shownScore?.let(::scoreColor) ?: Color.Gray, shape = RoundedCornerShape(12.dp)) {
                        Text(shownScore?.let { "%.2f".format(it) } ?: "—", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeCover(
    anime: Anime,
    coverRepository: CoverRepository,
    onDownloadRequested: () -> Unit,
    onCoverClick: () -> Unit,
    modifier: Modifier = Modifier,
    allowDownload: Boolean = true
) {
    val pathFlow = remember(anime.id, anime.mediaType, anime.localCoverPath) {
        if (anime.mediaType == MediaType.ANIME) coverRepository.observeLocalCoverPath(anime.id)
        else flowOf(anime.localCoverPath)
    }
    val immediatePath = remember(anime.id, anime.localCoverPath) {
        coverRepository.immediateLocalCoverPath(anime.id, anime.localCoverPath)
    }
    val localPath by pathFlow.collectAsState(initial = immediatePath)
    val downloadStateFlow = remember(anime.id, anime.mediaType) {
        if (anime.mediaType == MediaType.ANIME) coverRepository.observeDownloadState(anime.id)
        else flowOf(CoverDownloadState.IDLE)
    }
    val downloadState by downloadStateFlow.collectAsState(initial = CoverDownloadState.IDLE)
    var invalidLocalPath by remember(anime.id, localPath) { mutableStateOf(false) }
    val effectiveLocalPath = localPath.takeUnless { invalidLocalPath }
    var bitmap by remember(anime.id, effectiveLocalPath) {
        mutableStateOf(effectiveLocalPath?.let { coverRepository.cachedBitmap(anime.id, it) })
    }

    LaunchedEffect(anime.id, effectiveLocalPath) {
        val currentLocalPath = effectiveLocalPath
        if (currentLocalPath != null && bitmap == null) {
            val loaded = coverRepository.loadBitmap(anime.id, currentLocalPath)
            if (loaded == null) {
                if (anime.mediaType == MediaType.ANIME) {
                    coverRepository.invalidateLocalCover(anime, currentLocalPath)
                } else {
                    invalidLocalPath = true
                }
            } else {
                bitmap = loaded
            }
        } else if (currentLocalPath == null) {
            bitmap = null
        }
    }

    val image = bitmap
    when {
        image != null -> Image(
            bitmap = image.asImageBitmap(),
            contentDescription = anime.title,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    dispatchCoverTap(effectiveLocalPath, onDownloadRequested, onCoverClick)
                }
        )
        effectiveLocalPath == null -> DefaultCover(
            modifier = modifier,
            downloading = downloadState == CoverDownloadState.DOWNLOADING,
            enabled = allowDownload,
            onClick = { dispatchCoverTap(null, onDownloadRequested, onCoverClick) }
        )
        else -> Box(modifier.background(Color(0xFF1A1F2E)).clip(RoundedCornerShape(10.dp)))
    }
}

@Composable
private fun DefaultCover(
    modifier: Modifier = Modifier,
    downloading: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .background(color = Color(0xFF1A1F2E))
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled && !downloading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Image, contentDescription = stringResource(R.string.cover_download), tint = Color.Gray, modifier = Modifier.size(22.dp))
        if (downloading) {
            CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp, color = Cyan)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverViewerDialog(
    anime: Anime,
    coverRepository: CoverRepository,
    onDismiss: () -> Unit,
    onDeleteConfirmed: ((Boolean) -> Unit) -> Unit
) {
    val pathFlow = remember(anime.id, anime.mediaType, anime.localCoverPath) {
        if (anime.mediaType == MediaType.ANIME) coverRepository.observeLocalCoverPath(anime.id)
        else flowOf(anime.localCoverPath)
    }
    val initialPath = remember(anime.id, anime.localCoverPath) {
        if (anime.mediaType == MediaType.ANIME) {
            coverRepository.immediateLocalCoverPath(anime.id, anime.localCoverPath)
        } else {
            anime.localCoverPath
        }
    }
    val localPath by pathFlow.collectAsState(initial = initialPath)
    var bitmap by remember(anime.id, localPath) {
        mutableStateOf(localPath?.let { coverRepository.cachedBitmap(anime.id, it) })
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val imageAspectRatio = bitmap?.let { image ->
        image.width.toFloat().div(image.height.toFloat()).takeIf { it.isFinite() && it > 0f }
    } ?: (2f / 3f)

    LaunchedEffect(anime.id, localPath) {
        val currentPath = localPath
        if (currentPath == null) {
            onDismiss()
        } else if (bitmap == null) {
            val loaded = coverRepository.loadBitmap(anime.id, currentPath)
            if (loaded == null) {
                if (anime.mediaType == MediaType.ANIME) coverRepository.invalidateLocalCover(anime, currentPath)
                else onDismiss()
            } else {
                bitmap = loaded
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            val viewerWidth = minOf(maxWidth, 560.dp)
            val maxImageHeight = (maxHeight - 152.dp).coerceAtLeast(120.dp)
            val imageHeight = minOf(viewerWidth / imageAspectRatio, maxImageHeight)
            Surface(
                modifier = Modifier.width(viewerWidth).wrapContentHeight(),
                color = Color(0xFF08090E),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimeTopAppBar(anime.localizedDisplayTitle(), onDismiss)
                    Box(
                        modifier = Modifier.fillMaxWidth().height(imageHeight).background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        bitmap?.let { image ->
                            Image(
                                bitmap = image.asImageBitmap(),
                                contentDescription = anime.title,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } ?: CircularProgressIndicator(color = Cyan)
                    }
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        enabled = !deleting,
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cover_delete))
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text(stringResource(R.string.cover_delete_title)) },
            text = { Text(stringResource(R.string.cover_delete_explanation)) },
            dismissButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = { confirmDelete = false }
                ) { Text(stringResource(R.string.action_cancel)) }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        onDeleteConfirmed { deleted ->
                            deleting = false
                            if (!deleted) confirmDelete = false
                        }
                    }
                ) { Text(stringResource(R.string.action_delete), color = Coral) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RatingScreen(
    anime: Anime,
    initial: RatingDraft,
    settings: AppSettingsState,
    onBack: () -> Unit,
    onSaveElaborate: (RatingDraft, Double?, String) -> Unit,
    onSaveSimple: (RatingDraft, Double, Double) -> Unit,
    onDelete: () -> Unit
) {
    val config = settings.ratingConfig
    val elaborate = settings.elaborateRatingEnabled
    val profiles = settings.ratingProfiles.profiles
    val initialProfile = remember(initial.profileId, profiles) {
        settings.ratingProfiles.resolveProfile(initial.profileId)
    }
    var selectedProfileId by remember(anime.id, initialProfile.id) { mutableStateOf(initialProfile.id) }
    val selectedProfile = settings.ratingProfiles.resolveProfile(selectedProfileId)
    val calculationConfig = config.withProfile(selectedProfile)
    val defaultDraft = remember(anime.id, initial, config.categories, elaborate) {
        if (elaborate && initial.categoryScores.isEmpty()) initial.withDefaultCategoryScores(config) else initial
    }
    var draft by remember(anime.id, defaultDraft) { mutableStateOf(defaultDraft) }
    val initialSimpleQuality = remember(anime.id, initial.qualityScore, initial.finalScore, initial.additiveScore) {
        val candidate = initial.qualityScore
            ?: initial.finalScore?.minus(initial.additiveScore)
            ?: 5.0
        snapRatingValue(candidate.coerceIn(RATING_MIN, RATING_MAX)) ?: 5.0
    }
    var simpleQuality by remember(anime.id, initialSimpleQuality) { mutableDoubleStateOf(initialSimpleQuality) }
    var directFinalScore by remember(anime.id, elaborate, initial.finalScore, defaultDraft, calculationConfig) {
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
    var showDirectScoreDialog by remember(anime.id) { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val base = if (elaborate) draft.baseScore(calculationConfig) else simpleQuality
    val calculatedFinal = directFinalScore ?: if (elaborate) {
        draft.calculatedFinalScore(calculationConfig)
    } else {
        finalScoreFromQuality(simpleQuality, draft.additiveScore)
    }
    val stickyScore = settings.stickyScore

    Scaffold(
        containerColor = Bg,
        topBar = {
            AnimeTopAppBar(anime.localizedDisplayTitle(), onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 36.dp)
        ) {
            if (stickyScore) {
                stickyHeader {
                    ScoreHeader(base, draft.additiveScore, calculatedFinal, config) {
                        showDirectScoreDialog = true
                    }
                }
            } else {
                item {
                    ScoreHeader(base, draft.additiveScore, calculatedFinal, config) {
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
                            directFinalScore = null
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
                                color = Color.Gray,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                            )
                            Text(
                                stringResource(R.string.score_out_of_ten, simpleQuality),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = Cyan
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

@Composable
private fun RatingProfileSelector(
    profiles: List<RatingProfile>,
    selected: RatingProfile,
    onSelected: (RatingProfile) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Box {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.rating_profile), color = Color.Gray, fontSize = 13.sp)
                    Text(localizedProfileName(selected), fontWeight = FontWeight.Bold, color = Cyan)
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(localizedProfileName(profile)) },
                        onClick = {
                            expanded = false
                            onSelected(profile)
                        },
                        leadingIcon = {
                            if (profile.id == selected.id) Icon(Icons.Default.Check, contentDescription = null)
                        }
                    )
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
    onFinalScoreClick: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(stringResource(R.string.score_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        stringResource(R.string.score_quality, base?.let { "%.2f".format(it) } ?: "—"),
                        color = Color.LightGray
                    )
                    if (config.additiveBonus != null) {
                        Text(
                            stringResource(
                                R.string.score_bonus_value,
                                localizedBonusName(config.additiveBonus),
                                additive
                            ),
                            color = Magenta
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
    Surface(
        modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
        color = scoreColor(score ?: 0.0),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            score?.let { "%.2f".format(it) } ?: "—",
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            color = Color.Black,
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
                color = Magenta
            )
            Text(stringResource(R.string.bonus_explanation), color = Color.Gray, fontSize = 13.sp)
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
    var input by remember(initialValue) {
        mutableStateOf(initialValue?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty())
    }
    val parsed = parseDirectRatingValue(input)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.score_direct_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.score_direct_explanation), color = Color.Gray)
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
        Cyan
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
            Text(localizedCategoryDescription(category), color = Color.Gray, fontSize = 13.sp)
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
                    Text(scoreLabel(score), color = Color.LightGray, fontSize = 13.sp)
                    Text(stringResource(R.string.score_out_of_ten, score), fontWeight = FontWeight.Bold)
                }
            } else {
                Text(
                    stringResource(R.string.score_not_applicable_explanation),
                    color = Color.Gray,
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
private fun DiscreteSlider(
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
    val inactiveColor = Color(0xFF4B5268)
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
                    color = Bg,
                    start = Offset(thumbCenterX - 8.dp.toPx(), centerY),
                    end = Offset(thumbCenterX + 8.dp.toPx(), centerY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Butt
                )
                tickFractions.forEach { fraction ->
                    drawCircle(
                        color = if (fraction <= activeFraction) Bg else activeColor,
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

@Composable
private fun Top3Podium(
    top3: List<Anime>,
    sortOrder: RankingSortOrder,
    coverRepository: CoverRepository,
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
                    coverRepository = coverRepository,
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
    coverRepository: CoverRepository,
    onCoverDownload: () -> Unit,
    onCoverOpen: () -> Unit,
    onOpenRating: () -> Unit
) {
    val isFirst = position == 1
    val podiumWidth = (artwork.sourceWidth * globalScale).dp
    val podiumHeight = (artwork.sourceHeight * globalScale).dp
    val coverSize = minOf(if (isFirst) 120.dp else 96.dp, podiumWidth)
    val shownScore = anime.rankingDisplayScore(sortOrder)
    val scoreColorValue = shownScore?.let(::scoreColor) ?: Color.Gray

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
                color = Color.Black,
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
                anime.coverTarget(),
                coverRepository,
                onCoverDownload,
                onCoverOpen,
                Modifier
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

@Composable
private fun SettingsScreen(settings: AppSettingsState, catalogCount: Int, viewModel: RankOfflineViewModel) {
    var destination by remember { mutableStateOf(SettingsDestination.HOME) }
    val preloadProgress by viewModel.coverPreloadProgress.collectAsState()
    val downloadedCoverCount by viewModel.downloadedCoverCount.collectAsState()
    val goBack = {
        destination = when (destination) {
            SettingsDestination.GENERAL_DOWNLOADS -> SettingsDestination.GENERAL
            SettingsDestination.RATING_PROFILES,
            SettingsDestination.RATING_CATEGORIES -> SettingsDestination.RATING
            else -> SettingsDestination.HOME
        }
    }

    BackHandler(enabled = destination != SettingsDestination.HOME, onBack = goBack)

    when (destination) {
        SettingsDestination.HOME -> SettingsHomeScreen(
            activeMediaType = settings.activeMediaType,
            onMediaTypeSelected = viewModel::updateActiveMediaType,
            onGeneral = { destination = SettingsDestination.GENERAL },
            onRating = { destination = SettingsDestination.RATING },
            onIcon = { destination = SettingsDestination.ICON },
            onAbout = { destination = SettingsDestination.ABOUT }
        )
        SettingsDestination.GENERAL -> GeneralSettingsScreen(
            settings = settings,
            onStickyScoreChange = viewModel::updateStickyScore,
            onShowImagesChange = viewModel::updateShowImages,
            onLanguage = { destination = SettingsDestination.LANGUAGE },
            onPopularityAndCovers = { destination = SettingsDestination.GENERAL_DOWNLOADS },
            onBack = goBack
        )
        SettingsDestination.GENERAL_DOWNLOADS -> PopularityAndCoversScreen(
            activeMediaType = settings.activeMediaType,
            preloadProgress = preloadProgress,
            downloadedCoverCount = downloadedCoverCount,
            onDownloadRated = viewModel::downloadRatedCovers,
            onCancelPreload = viewModel::cancelCoverPreload,
            onClearCovers = viewModel::clearDownloadedCovers,
            onVisibilityChanged = viewModel::setGeneralSettingsVisible,
            onBack = goBack
        )
        SettingsDestination.LANGUAGE -> LanguageSettingsScreen(
            selectedLanguage = settings.language,
            onLanguageSelected = viewModel::updateLanguage,
            onBack = { destination = SettingsDestination.GENERAL }
        )
        SettingsDestination.RATING -> RatingSettingsScreen(
            elaborateRatingEnabled = settings.elaborateRatingEnabled,
            onElaborateRatingEnabledChange = viewModel::updateElaborateRatingEnabled,
            onProfiles = { destination = SettingsDestination.RATING_PROFILES },
            onCategories = { destination = SettingsDestination.RATING_CATEGORIES },
            onBack = goBack
        )
        SettingsDestination.RATING_CATEGORIES -> RatingCategoriesScreen(
            ratingConfig = settings.ratingConfig,
            onUpdate = viewModel::updateRatingCategory,
            onCreate = viewModel::createRatingCategory,
            onDeleteCategory = viewModel::deleteRatingCategory,
            onReset = viewModel::resetRatingCategories,
            onBack = goBack
        )
        SettingsDestination.RATING_PROFILES -> RatingProfilesScreen(
            profilesConfig = settings.ratingProfiles,
            categories = settings.ratingConfig.categories,
            onUpdate = viewModel::updateRatingProfiles,
            onDelete = viewModel::deleteRatingProfile,
            onReset = viewModel::resetRatingProfiles,
            onBack = goBack
        )
        SettingsDestination.ICON -> IconSettingsScreen(
            selectedIcon = settings.appIcon,
            onIconSelected = viewModel::updateAppIcon,
            onBack = goBack
        )
        SettingsDestination.ABOUT -> LicenceAboutScreen(catalogCount = catalogCount, onBack = goBack)
    }
}

private enum class SettingsDestination {
    HOME,
    GENERAL,
    GENERAL_DOWNLOADS,
    LANGUAGE,
    RATING,
    RATING_PROFILES,
    RATING_CATEGORIES,
    ICON,
    ABOUT
}

@Composable
private fun SettingsHomeScreen(
    activeMediaType: MediaType,
    onMediaTypeSelected: (MediaType) -> Unit,
    onGeneral: () -> Unit,
    onRating: () -> Unit,
    onIcon: () -> Unit,
    onAbout: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MediaScreenTitle(
            title = stringResource(R.string.settings_title),
            activeMediaType = activeMediaType,
            onMediaTypeSelected = onMediaTypeSelected
        )
        SettingsMenuItem(
            icon = Icons.Default.Settings,
            title = stringResource(R.string.settings_general_title),
            description = stringResource(R.string.settings_general_description),
            onClick = onGeneral
        )
        SettingsMenuItem(
            icon = Icons.Default.Star,
            title = stringResource(R.string.settings_rating_title),
            description = stringResource(R.string.settings_rating_description),
            onClick = onRating
        )
        SettingsMenuItem(
            icon = Icons.Default.Image,
            title = stringResource(R.string.settings_icon_title),
            description = stringResource(R.string.settings_icon_description),
            onClick = onIcon
        )
        SettingsMenuItem(
            icon = Icons.Default.Info,
            title = stringResource(R.string.settings_about_title),
            description = stringResource(R.string.settings_about_description),
            onClick = onAbout
        )
    }
}

@Composable
private fun IconSettingsScreen(
    selectedIcon: AppIcon,
    onIconSelected: (AppIcon) -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.settings_icon_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.icon_instructions),
                    color = Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(AppIcon.entries) { icon ->
                val selected = icon == selectedIcon
                val displayName = stringResource(icon.displayNameResource)
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (!selected) onIconSelected(icon)
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) Cyan.copy(alpha = 0.16f) else Panel
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(icon.previewResource),
                            contentDescription = stringResource(R.string.icon_content_description, displayName),
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(14.dp))
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = if (selected) stringResource(R.string.icon_selected, displayName) else displayName,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) Cyan else Color.White
                        )
                        RadioButton(
                            selected = selected,
                            onClick = { if (!selected) onIconSelected(icon) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Cyan)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, fontSize = 13.sp, color = Color.Gray)
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.settings_open_item, title),
                tint = Color.LightGray
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubscreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            content()
        }
    }
}

@Composable
private fun LanguageSettingsScreen(
    selectedLanguage: SupportedLanguage,
    onLanguageSelected: (SupportedLanguage) -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.language_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.language_description),
                    color = Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(SupportedLanguage.entries) { language ->
                val selected = language == selectedLanguage
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (!selected) onLanguageSelected(language)
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) Cyan.copy(alpha = 0.16f) else Panel
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(language.displayNameResource),
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) Cyan else Color.White
                        )
                        RadioButton(
                            selected = selected,
                            onClick = { if (!selected) onLanguageSelected(language) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralSettingsScreen(
    settings: AppSettingsState,
    onStickyScoreChange: (Boolean) -> Unit,
    onShowImagesChange: (Boolean) -> Unit,
    onLanguage: () -> Unit,
    onPopularityAndCovers: () -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.settings_general_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.general_display_section), fontWeight = FontWeight.Bold, color = Cyan)
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.general_sticky_score), fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.general_sticky_score_description), fontSize = 13.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = settings.stickyScore,
                                onCheckedChange = onStickyScoreChange
                            )
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.general_show_images), fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.general_show_images_description), fontSize = 13.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = settings.showImages,
                                onCheckedChange = onShowImagesChange
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = onLanguage).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.language_title), fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(settings.language.displayNameResource),
                                    fontSize = 13.sp,
                                    color = Color.Gray
                                )
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = stringResource(R.string.settings_open_item, stringResource(R.string.language_title)),
                                tint = Color.LightGray
                            )
                        }
                    }
                }
            }
            item {
                SettingsMenuItem(
                    icon = Icons.Default.CloudDownload,
                    title = stringResource(R.string.covers_settings_title),
                    description = stringResource(R.string.covers_settings_description),
                    onClick = onPopularityAndCovers
                )
            }
        }
    }
}

@Composable
private fun PopularityAndCoversScreen(
    activeMediaType: MediaType,
    preloadProgress: CoverPreloadProgress,
    downloadedCoverCount: Int,
    onDownloadRated: () -> Unit,
    onCancelPreload: () -> Unit,
    onClearCovers: () -> Unit,
    onVisibilityChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var showClearConfirmation by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onVisibilityChanged(true)
        onDispose { onVisibilityChanged(false) }
    }
    SettingsSubscreen(stringResource(R.string.covers_settings_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.covers_settings_explanation),
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        Text(
                            stringResource(R.string.covers_downloaded_count, downloadedCoverCount),
                            fontWeight = FontWeight.SemiBold,
                            color = Color.LightGray,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            stringResource(activeMediaType.labelResource),
                            color = Cyan,
                            fontWeight = FontWeight.SemiBold
                        )
                        Button(
                            onClick = onDownloadRated,
                            enabled = !preloadProgress.isActive,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.covers_download_rated))
                        }
                        PopularityAndCoverProgress(preloadProgress)
                        if (preloadProgress.isActive) {
                            TextButton(
                                onClick = onCancelPreload,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.action_cancel)) }
                        }
                        OutlinedButton(
                            onClick = { showClearConfirmation = true },
                            enabled = !preloadProgress.isActive,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.covers_clear))
                        }
                    }
                }
            }
        }
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.covers_clear_title)) },
            text = { Text(stringResource(R.string.covers_clear_explanation)) },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirmation = false
                    onClearCovers()
                }) { Text(stringResource(R.string.action_delete), color = Coral) }
            }
        )
    }
}

@Composable
private fun PopularityAndCoverProgress(preloadProgress: CoverPreloadProgress) {
    if (preloadProgress.status == CoverPreloadStatus.IDLE) return
    Spacer(Modifier.height(12.dp))
    Text(
        when (preloadProgress.status) {
            CoverPreloadStatus.COMPLETED -> stringResource(R.string.covers_completed)
            CoverPreloadStatus.FAILED -> stringResource(R.string.covers_download_interrupted)
            else -> stringResource(R.string.covers_downloading_rated)
        },
        fontWeight = FontWeight.SemiBold
    )
    Text(
        stringResource(R.string.covers_progress, preloadProgress.processed, preloadProgress.target),
        color = Color.LightGray,
        modifier = Modifier.padding(top = 10.dp)
    )
    LinearProgressIndicator(
        progress = { preloadProgress.fraction },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
    Text(
        stringResource(
            R.string.covers_progress_details_rated,
            preloadProgress.existing,
            preloadProgress.downloaded,
            preloadProgress.errors
        ),
        color = Color.Gray,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 6.dp)
    )
    when {
        preloadProgress.status == CoverPreloadStatus.COMPLETED -> Text(
            stringResource(R.string.covers_completed),
            color = Mint,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        preloadProgress.status == CoverPreloadStatus.FAILED -> Text(
            stringResource(R.string.covers_download_interrupted),
            color = Coral,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun RatingSettingsScreen(
    elaborateRatingEnabled: Boolean,
    onElaborateRatingEnabledChange: (Boolean) -> Unit,
    onProfiles: () -> Unit,
    onCategories: () -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.settings_rating_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(R.string.rating_mode), fontWeight = FontWeight.Bold, color = Magenta)
                        listOf(
                            false to stringResource(R.string.rating_simple),
                            true to stringResource(R.string.rating_elaborate)
                        ).forEach { (isElaborate, label) ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onElaborateRatingEnabledChange(isElaborate)
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = elaborateRatingEnabled == isElaborate,
                                    onClick = { onElaborateRatingEnabledChange(isElaborate) }
                                )
                                Text(label)
                            }
                        }
                        Text(
                            stringResource(R.string.rating_elaborate_description),
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            item {
                SettingsMenuItem(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.rating_profiles),
                    description = stringResource(R.string.rating_profiles_description),
                    onClick = onProfiles
                )
            }
            item {
                SettingsMenuItem(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = stringResource(R.string.rating_categories),
                    description = stringResource(R.string.rating_categories_description),
                    onClick = onCategories
                )
            }
        }
    }
}

@Composable
private fun RatingCategoriesScreen(
    ratingConfig: RatingSystemConfig,
    onUpdate: (RatingCategory) -> Unit,
    onCreate: (RatingCategory) -> Unit,
    onDeleteCategory: (RatingCategory) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    var confirmReset by remember { mutableStateOf(false) }
    val newCategoryName = stringResource(R.string.rating_new_category_name)
    val newCategoryDescription = stringResource(R.string.rating_new_category_description)
    SettingsSubscreen(stringResource(R.string.rating_categories), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(ratingConfig.categories.sortedBy { it.order }, key = { it.id }) { category ->
                RatingCategoryEditor(category, onUpdate, onDelete = { onDeleteCategory(category) })
            }
            item {
                Button(
                    onClick = {
                        onCreate(
                            RatingCategory(
                                id = "custom_${UUID.randomUUID()}",
                                name = newCategoryName,
                                description = newCategoryDescription,
                                weight = 0.0,
                                color = "#7EC7FF",
                                order = (ratingConfig.categories.maxOfOrNull { it.order } ?: -1) + 1
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.rating_add_category)) }
            }
            item {
                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.rating_reset_categories))
                }
            }
        }
    }
    if (confirmReset) {
        ConfirmationDialog(
            title = stringResource(R.string.rating_reset_categories),
            text = stringResource(R.string.rating_reset_categories_confirmation),
            onDismiss = { confirmReset = false },
            onConfirm = { confirmReset = false; onReset() }
        )
    }
}

@Composable
private fun RatingProfilesScreen(
    profilesConfig: RatingProfilesConfig,
    categories: List<RatingCategory>,
    onUpdate: (RatingProfilesConfig) -> Unit,
    onDelete: (String) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    var confirmReset by remember { mutableStateOf(false) }
    val newProfileName = stringResource(R.string.rating_new_profile_name)
    SettingsSubscreen(stringResource(R.string.rating_profiles), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(profilesConfig.profiles, key = { it.id }) { profile ->
                RatingProfileEditor(
                    profile = profile,
                    categories = categories.sortedBy { it.order },
                    canDelete = profilesConfig.profiles.size > 1,
                    onUpdate = { changed ->
                        onUpdate(profilesConfig.copy(profiles = profilesConfig.profiles.map {
                            if (it.id == changed.id) changed else it
                        }))
                    },
                    onDelete = { onDelete(profile.id) }
                )
            }
            item {
                Button(
                    onClick = {
                        val profile = RatingProfile(
                            id = "custom_${UUID.randomUUID()}",
                            name = newProfileName,
                            weights = categories.associate { it.id to 5.0 }
                        )
                        onUpdate(profilesConfig.copy(profiles = profilesConfig.profiles + profile))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.rating_create_profile)) }
            }
            item {
                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.rating_reset_profiles))
                }
            }
        }
    }
    if (confirmReset) {
        ConfirmationDialog(
            title = stringResource(R.string.rating_reset_profiles),
            text = stringResource(R.string.rating_reset_profiles_confirmation),
            onDismiss = { confirmReset = false },
            onConfirm = { confirmReset = false; onReset() }
        )
    }
}

@Composable
private fun RatingProfileEditor(
    profile: RatingProfile,
    categories: List<RatingCategory>,
    canDelete: Boolean,
    onUpdate: (RatingProfile) -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirmation by remember(profile.id) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = localizedProfileName(profile),
                    onValueChange = { onUpdate(profile.copy(name = it)) },
                    label = { Text(stringResource(R.string.rating_profile_name)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(enabled = canDelete, onClick = { showDeleteConfirmation = true }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.rating_delete_profile), tint = if (canDelete) Coral else Color.Gray)
                }
            }
            categories.forEach { category ->
                val weight = profile.weights[category.id] ?: 0.0
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(localizedCategoryName(category), modifier = Modifier.width(100.dp), maxLines = 1)
                    DiscreteSlider(
                        value = weight,
                        onValueChange = { value ->
                            snapWeight(value)?.let { onUpdate(profile.copy(weights = profile.weights + (category.id to it))) }
                        },
                        min = WEIGHT_MIN,
                        max = WEIGHT_MAX,
                        step = WEIGHT_STEP,
                        accessibilityLabel = stringResource(R.string.category_weight_slider_accessibility),
                        valueDescription = stringResource(R.string.category_weight_percent, weight.toInt()),
                        modifier = Modifier.weight(1f)
                    )
                    Text(stringResource(R.string.category_weight_percent, weight.toInt()), modifier = Modifier.width(52.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            if (!canDelete) {
                Text(stringResource(R.string.rating_last_profile_required), color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
    if (showDeleteConfirmation) {
        ConfirmationDialog(
            title = stringResource(R.string.rating_delete_profile),
            text = stringResource(R.string.rating_delete_profile_confirmation, localizedProfileName(profile)),
            onDismiss = { showDeleteConfirmation = false },
            onConfirm = { showDeleteConfirmation = false; onDelete() }
        )
    }
}

@Composable
private fun ConfirmationDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_apply)) } }
    )
}

@Composable
private fun LicenceAboutScreen(catalogCount: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val currentLocale = Locale.getDefault()
    val versionName = remember(context.packageName) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    val formattedCatalogCount = remember(catalogCount, currentLocale) {
        NumberFormat.getIntegerInstance(currentLocale).format(catalogCount)
    }
    SettingsSubscreen(stringResource(R.string.settings_about_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                AboutCard("RankOffline", Mint) {
                    Text(
                        stringResource(R.string.about_version, versionName),
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        stringResource(R.string.catalog_local_count, formattedCatalogCount),
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    AboutBody(stringResource(R.string.about_summary_multimedia))
                    AboutBody(stringResource(R.string.about_rankoffline_licence))
                    AttributionLink(
                        stringResource(R.string.about_source_code),
                        AttributionRegistry.SOURCE_CODE_URL
                    )
                }
            }
            item {
                AboutCard(stringResource(R.string.about_data_sources), Cyan) {
                    AboutProviderSection(
                        stringResource(R.string.nav_anime),
                        stringResource(R.string.about_anime_sources_description),
                        AttributionRegistry.providers.take(4)
                    )
                    AttributionLink(
                        "manami-project/anime-offline-database",
                        AttributionRegistry.ANIME_OFFLINE_DATABASE_ORIGINAL_URL
                    )
                    AboutProviderSection(
                        stringResource(R.string.media_manga),
                        stringResource(R.string.about_manga_source_description),
                        listOf(AttributionRegistry.providers[4])
                    )
                    Text(
                        stringResource(R.string.about_movies_series),
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Image(
                        painter = painterResource(R.drawable.tmdb_primary_short_blue),
                        contentDescription = stringResource(R.string.about_tmdb_logo_description),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .width(114.dp)
                            .height(49.dp)
                            .clickable { openTrustedUrl(context, AttributionRegistry.TMDB_URL) }
                    )
                    AboutBody(stringResource(R.string.about_tmdb_source_description), topPadding = 4.dp)
                    AboutBody(stringResource(R.string.about_tmdb_notice), topPadding = 6.dp)
                    AttributionLink("TMDB", AttributionRegistry.TMDB_URL)
                    AboutProviderSection(
                        stringResource(R.string.media_books),
                        stringResource(R.string.about_open_library_source_description),
                        listOf(AttributionRegistry.providers[6])
                    )
                }
            }
            item {
                AboutCard(stringResource(R.string.about_data_licences), Magenta) {
                    AboutBody(stringResource(R.string.about_anime_database_licences))
                    AboutBody(stringResource(R.string.about_alias_database_licences))
                    AboutBody(stringResource(R.string.about_cc0_scope))
                    AboutBody(stringResource(R.string.about_open_library_licence_caveat))
                    AttributionLink("ODbL 1.0", AttributionRegistry.ODBL_URL)
                    AttributionLink("DbCL 1.0", AttributionRegistry.DBCL_URL)
                    AttributionLink("CC0 1.0", AttributionRegistry.CC0_URL)
                }
            }
            item {
                AboutCard(stringResource(R.string.about_open_source_software), Orange) {
                    AboutBody(stringResource(R.string.about_software_licences))
                    AboutBody(stringResource(R.string.about_packaged_notices))
                    AttributionLink("GPL-3.0", AttributionRegistry.GPL_URL)
                    AttributionLink("Apache-2.0", AttributionRegistry.APACHE_URL)
                }
            }
            item {
                AboutCard(stringResource(R.string.about_legal_trademarks), Coral) {
                    AboutBody(stringResource(R.string.about_non_affiliation))
                    AboutBody(stringResource(R.string.about_rankoffline_trademarks))
                    AttributionLink(
                        stringResource(R.string.about_trademark_policy),
                        AttributionRegistry.TRADEMARKS_URL
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutCard(
    title: String,
    titleColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = titleColor)
            content()
        }
    }
}

@Composable
private fun AboutBody(text: String, topPadding: androidx.compose.ui.unit.Dp = 8.dp) {
    Text(
        text,
        color = Color.LightGray,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = topPadding)
    )
}

@Composable
private fun AboutProviderSection(
    heading: String,
    description: String,
    providers: List<ProviderAttribution>
) {
    Text(
        heading,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = Modifier.padding(top = 12.dp)
    )
    AboutBody(description, topPadding = 4.dp)
    providers.forEach { provider -> AttributionLink(provider.name, provider.url) }
}

@Composable
private fun AttributionLink(label: String, url: String) {
    val context = LocalContext.current
    TextButton(
        onClick = { openTrustedUrl(context, url) },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
    ) {
        Text(label, color = Cyan, fontSize = 13.sp)
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = Cyan,
            modifier = Modifier.padding(start = 4.dp).size(14.dp)
        )
    }
}

private fun openTrustedUrl(context: Context, url: String) {
    if (url !in AttributionRegistry.trustedUrls) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun RatingCategoryEditor(category: RatingCategory, onUpdate: (RatingCategory) -> Unit, onDelete: () -> Unit) {
    val swatch = try {
        Color(android.graphics.Color.parseColor(category.color))
    } catch (_: Exception) {
        Color.White
    }
    var showColorPicker by remember(category.id) { mutableStateOf(false) }
    var showDeleteConfirmation by remember(category.id) { mutableStateOf(false) }
    val colorContentDescription = stringResource(R.string.category_color_content_description)

    Card(
        colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.92f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = localizedCategoryName(category),
                    onValueChange = { onUpdate(category.copy(name = it)) },
                    label = { Text(stringResource(R.string.category_name)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(onClick = { showDeleteConfirmation = true }) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red)
                }
            }
            OutlinedTextField(
                value = localizedCategoryDescription(category),
                onValueChange = { onUpdate(category.copy(description = it)) },
                label = { Text(stringResource(R.string.category_description)) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.category_color), color = Color.LightGray, modifier = Modifier.width(52.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(swatch)
                        .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .clickable { showColorPicker = true }
                        .semantics {
                            contentDescription = colorContentDescription
                        }
                )
            }
        }
    }
    if (showColorPicker) {
        CategoryColorPickerDialog(
            initialColor = category.color,
            onDismiss = { showColorPicker = false },
            onApply = { color ->
                category.withHexColor(color)?.let(onUpdate)
                showColorPicker = false
            }
        )
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.category_delete_title)) },
            text = {
                Text(stringResource(R.string.category_delete_explanation, localizedCategoryName(category)))
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.action_delete), color = Coral)
                }
            }
        )
    }
}

@Composable
private fun CategoryColorPickerDialog(
    initialColor: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    var hexInput by remember(initialColor) {
        mutableStateOf(normalizeHexColor(initialColor) ?: "#19D5E5")
    }
    val validColor = normalizeHexColor(hexInput)
    val previewColor = validColor?.let { Color(android.graphics.Color.parseColor(it)) } ?: Color.Transparent
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.color_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(previewColor, RoundedCornerShape(10.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                )
                Text(stringResource(R.string.color_palette), fontWeight = FontWeight.SemiBold)
                CategoryColorPalette.chunked(5).forEach { rowColors ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        rowColors.forEach { hex ->
                            val selected = validColor == hex
                            val swatchColor = Color(android.graphics.Color.parseColor(hex))
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(swatchColor)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) Color.White else Color.White.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { hexInput = hex },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = if (swatchColor.luminance() > 0.45f) Color.Black else Color.White
                                    )
                                }
                            }
                        }
                    }
                }
                Text(stringResource(R.string.color_custom), fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { hexInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.color_hex_label)) },
                    supportingText = {
                        if (hexInput.isNotBlank() && validColor == null) {
                            Text(stringResource(R.string.color_invalid))
                        }
                    },
                    isError = hexInput.isNotBlank() && validColor == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = validColor != null,
                onClick = { validColor?.let(onApply) }
            ) { Text(stringResource(R.string.action_apply)) }
        }
    )
}

@Composable
private fun CatalogSortOrder.criterionLabel(): String = when (this) {
    CatalogSortOrder.POPULARITY,
    CatalogSortOrder.POPULARITY_REVERSE -> stringResource(R.string.sort_popularity)
    CatalogSortOrder.TITLE_ASC,
    CatalogSortOrder.TITLE_DESC -> stringResource(R.string.sort_title_ascending)
}

@Composable
private fun RankingSortOrder.localizedLabel(): String = stringResource(
    when (this) {
        RankingSortOrder.FINAL_SCORE -> R.string.sort_final_score
        RankingSortOrder.QUALITY_SCORE -> R.string.sort_quality_score
        RankingSortOrder.PERSONAL_TASTE -> R.string.sort_personal_taste
        RankingSortOrder.CATEGORY_SCORE -> R.string.sort_category_score
    }
)

@Composable
private fun rankingSortLabel(
    sortOrder: RankingSortOrder,
    categoryId: String?,
    categories: List<RatingCategory>
): String {
    if (sortOrder != RankingSortOrder.CATEGORY_SCORE) return sortOrder.localizedLabel()
    val category = categories.firstOrNull { it.id == categoryId }
    return category?.let { localizedCategoryName(it) } ?: stringResource(R.string.sort_category_score)
}

@Composable
private fun scoreLabel(v: Double): String = stringResource(when {
    v >= 9.75 -> R.string.score_flawless
    v >= 8.75 -> R.string.score_amazing
    v >= 7.75 -> R.string.score_great
    v >= 6.75 -> R.string.score_good
    v >= 5.75 -> R.string.score_decent
    v >= 4.75 -> R.string.score_average
    v >= 3.75 -> R.string.score_subpar
    v >= 2.75 -> R.string.score_poor
    v >= 1.75 -> R.string.score_bad
    else -> R.string.score_unwatchable
})

@Composable
private fun localizedCategoryName(category: RatingCategory): String {
    val canonical = MediaType.entries.firstNotNullOfOrNull { mediaType ->
        DefaultSettings.canonicalCategory(mediaType, category.id)
            ?.takeIf { it.name == category.name && it.description == category.description }
            ?.let { mediaType to it }
    }
    val resource = canonical?.let { (mediaType, _) -> categoryNameResource(mediaType, category.id) }
    return resource?.let { stringResource(it) } ?: category.name
}

@Composable
private fun localizedCategoryDescription(category: RatingCategory): String {
    val canonicalMedia = MediaType.entries.firstOrNull { mediaType ->
        DefaultSettings.canonicalCategory(mediaType, category.id)?.description == category.description
    }
    val resource = canonicalMedia?.let { categoryDescriptionResource(it, category.id) }
    return resource?.let { stringResource(it) } ?: category.description
}

@Composable
private fun localizedProfileName(profile: RatingProfile): String {
    val isCanonical = MediaType.entries.any { mediaType ->
        DefaultSettings.canonicalProfile(mediaType, profile.id)?.name == profile.name
    }
    val resource = if (isCanonical) profileNameResource(profile.id) else null
    return resource?.let { stringResource(it) } ?: profile.name
}

private fun profileNameResource(id: String): Int? = when (id) {
    DefaultSettings.GENERAL_PROFILE_ID -> R.string.profile_general
    "narrative" -> R.string.profile_narrative
    "character_driven" -> R.string.profile_character_driven
    "visual_action" -> R.string.profile_visual_action
    "art_focused" -> R.string.profile_art_focused
    "prose_style" -> R.string.profile_prose_style
    "visual_direction" -> R.string.profile_visual_direction
    "technical_craft" -> R.string.profile_technical_craft
    "world_adventure" -> R.string.profile_world_adventure
    "atmospheric" -> R.string.profile_atmospheric
    else -> null
}

@Composable
private fun localizedBonusName(bonus: AdditiveBonus): String =
    if (bonus.name == DefaultSettings.defaultAdditiveBonus.name) {
        stringResource(R.string.bonus_personal_taste)
    } else {
        bonus.name
    }

private fun categoryNameResource(mediaType: MediaType, id: String): Int? = when (id) {
    "writing" -> when (mediaType) {
        MediaType.ANIME -> R.string.category_writing
        MediaType.MANGA -> R.string.category_manga_writing
        MediaType.BOOK -> R.string.category_book_writing
        MediaType.SERIES -> R.string.category_series_writing
        MediaType.MOVIE -> R.string.category_movie_writing
    }
    "characters" -> R.string.category_characters
    "engagement" -> R.string.category_engagement
    "visuals" -> R.string.category_visuals
    "worldbuilding" -> R.string.category_worldbuilding
    "audio" -> R.string.category_audio
    "art" -> R.string.category_art
    "paneling" -> R.string.category_paneling
    "prose" -> R.string.category_prose
    "structure" -> R.string.category_structure
    "direction" -> R.string.category_direction
    "editing" -> R.string.category_editing
    else -> null
}

private fun categoryDescriptionResource(mediaType: MediaType, id: String): Int? = when (mediaType) {
    MediaType.ANIME -> when (id) {
        "writing" -> R.string.category_writing_description
        "characters" -> R.string.category_characters_description
        "engagement" -> R.string.category_engagement_description
        "visuals" -> R.string.category_visuals_description
        "worldbuilding" -> R.string.category_worldbuilding_description
        "audio" -> R.string.category_audio_description
        else -> null
    }
    MediaType.MANGA -> when (id) {
        "writing" -> R.string.category_manga_writing_description
        "characters" -> R.string.category_manga_characters_description
        "art" -> R.string.category_art_description
        "paneling" -> R.string.category_paneling_description
        "worldbuilding" -> R.string.category_manga_worldbuilding_description
        else -> null
    }
    MediaType.BOOK -> when (id) {
        "writing" -> R.string.category_book_writing_description
        "characters" -> R.string.category_book_characters_description
        "prose" -> R.string.category_prose_description
        "worldbuilding" -> R.string.category_book_worldbuilding_description
        "structure" -> R.string.category_structure_description
        else -> null
    }
    MediaType.SERIES -> when (id) {
        "writing" -> R.string.category_series_writing_description
        "characters" -> R.string.category_series_characters_description
        "direction" -> R.string.category_series_direction_description
        "visuals" -> R.string.category_series_visuals_description
        "worldbuilding" -> R.string.category_series_worldbuilding_description
        "audio" -> R.string.category_series_audio_description
        else -> null
    }
    MediaType.MOVIE -> when (id) {
        "writing" -> R.string.category_movie_writing_description
        "characters" -> R.string.category_movie_characters_description
        "direction" -> R.string.category_movie_direction_description
        "visuals" -> R.string.category_movie_visuals_description
        "editing" -> R.string.category_editing_description
        "audio" -> R.string.category_movie_audio_description
        else -> null
    }
}

private fun scoreColor(v: Double): Color = when {
    v >= 9 -> Cyan
    v >= 8 -> Mint
    v >= 7 -> Color(0xFFA4CD78)
    v >= 5 -> Orange
    v >= 3 -> Coral
    else -> Color(0xFFF32B60)
}
