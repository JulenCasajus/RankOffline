package org.rankoffline.app

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.flow.collect
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import java.util.UUID

private val Bg = Color(0xFF0A0B12)
private val Panel = Color(0xFF121522)
private val Cyan = Color(0xFF19D5E5)
private val Magenta = Color(0xFFE04BCF)
private val Coral = Color(0xFFFF675D)
private val Orange = Color(0xFFFFAA55)
private val Mint = Color(0xFF62D6C8)

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
    val list by viewModel.searchResults.collectAsState()
    val ranked by viewModel.ranking.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val catalogSortOrder by viewModel.catalogSortOrder.collectAsState()
    val catalogCount by viewModel.catalogCount.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

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
                onSaveElaborate = { draft -> viewModel.saveElaborateRating(editor.anime.id, draft) },
                onSaveSimple = { draft, score -> viewModel.saveSimpleRating(editor.anime.id, draft, score) },
                onDelete = { viewModel.deleteRating(editor.anime.id) }
            )
        }
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
                    query = query,
                    catalogCount = catalogCount,
                    imagesEnabled = settings.showImages,
                    coverRepository = viewModel.coverRepository,
                    onQueryChange = viewModel::setSearchQuery,
                    sortOrder = catalogSortOrder,
                    onSortOrderChange = viewModel::setCatalogSortOrder,
                    onAnime = viewModel::openRating,
                    onCoverDownload = viewModel::downloadCover,
                    onCoverOpen = { coverViewerAnime = it },
                    errorMessage = errorMessage
                )
                1 -> RankingScreen(
                    ranked,
                    settings.showImages,
                    viewModel.coverRepository,
                    viewModel::openRating,
                    viewModel::downloadCover,
                    onCoverOpen = { coverViewerAnime = it }
                )
                else -> SettingsScreen(settings, viewModel)
            }
        }
    }
}

@Composable
private fun AnimeListScreen(
    list: List<Anime>,
    query: String,
    catalogCount: Int,
    imagesEnabled: Boolean,
    coverRepository: CoverRepository,
    onQueryChange: (String) -> Unit,
    sortOrder: CatalogSortOrder,
    onSortOrderChange: (CatalogSortOrder) -> Unit,
    onAnime: (Anime) -> Unit,
    onCoverDownload: (Anime) -> Unit,
    onCoverOpen: (Anime) -> Unit,
    errorMessage: UserMessage?
) {
    val listState = rememberLazyListState()
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val currentLocale = Locale.getDefault()
    val formattedCatalogCount = remember(catalogCount, currentLocale) {
        NumberFormat.getIntegerInstance(currentLocale).format(catalogCount)
    }

    LaunchedEffect(query, sortOrder) {
        if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
            listState.scrollToItem(0)
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Text("RankOffline", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.catalog_local_count, formattedCatalogCount),
                color = Color.LightGray,
                modifier = Modifier.weight(1f)
            )
            Box {
                OutlinedButton(
                    onClick = { sortMenuExpanded = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(sortOrder.shortLabel(), fontSize = 13.sp)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    CatalogSortOrder.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.menuLabel()) },
                            onClick = {
                                sortMenuExpanded = false
                                onSortOrderChange(option)
                            }
                        )
                    }
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
            placeholder = { Text(stringResource(R.string.search_anime_hint)) }
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { anime ->
                AnimeRow(
                    anime,
                    imagesEnabled,
                    coverRepository,
                    onCoverDownload = { onCoverDownload(anime) },
                    onCoverOpen = { onCoverOpen(anime) },
                    onClick = { onAnime(anime) }
                )
            }
            if (list.isEmpty()) item { Text(stringResource(R.string.no_search_results), color = Color.Gray, modifier = Modifier.padding(20.dp)) }
        }
    }
}

@Composable
private fun RankingScreen(
    ranked: List<Anime>,
    imagesEnabled: Boolean,
    coverRepository: CoverRepository,
    onAnime: (Anime) -> Unit,
    onCoverDownload: (Anime) -> Unit,
    onCoverOpen: (Anime) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().background(Bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(stringResource(R.string.ranking_title), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(
                    if (imagesEnabled) R.string.ranking_description_with_images
                    else R.string.ranking_description_without_images
                ),
                color = Color.LightGray
            )
            Spacer(Modifier.height(12.dp))
        }
        if (imagesEnabled) {
            val top3 = ranked.take(3)
            val rest = ranked.drop(3)
            if (top3.isNotEmpty()) {
                item { Top3Podium(top3, coverRepository, onCoverDownload, onCoverOpen, onAnime) }
            }
            itemsIndexed(rest, key = { _, anime -> anime.id }) { index, anime ->
                AnimeRow(
                    anime,
                    true,
                    coverRepository,
                    rank = index + 4,
                    onCoverDownload = { onCoverDownload(anime) },
                    onCoverOpen = { onCoverOpen(anime) },
                    onClick = { onAnime(anime) }
                )
            }
            if (rest.isEmpty() && top3.isEmpty()) {
                item { Text(stringResource(R.string.no_saved_ratings), color = Color.Gray, modifier = Modifier.padding(20.dp)) }
            }
        } else {
            itemsIndexed(ranked, key = { _, anime -> anime.id }) { index, anime ->
                AnimeRow(
                    anime,
                    false,
                    coverRepository,
                    rank = index + 1,
                    onCoverDownload = { onCoverDownload(anime) },
                    onCoverOpen = { onCoverOpen(anime) },
                    onClick = { onAnime(anime) }
                )
            }
            if (ranked.isEmpty()) {
                item { Text(stringResource(R.string.no_saved_ratings), color = Color.Gray, modifier = Modifier.padding(20.dp)) }
            }
        }
    }
}

@Composable
private fun AnimeRow(
    anime: Anime,
    showImages: Boolean,
    coverRepository: CoverRepository,
    rank: Int? = null,
    onCoverDownload: () -> Unit,
    onCoverOpen: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showImages) {
                AnimeCover(
                    anime,
                    coverRepository,
                    onCoverDownload,
                    onCoverOpen,
                    Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(12.dp))
            }
            Row(
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (rank != null) {
                    Text("${rank}.", color = Color.LightGray, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
                }
                Text(
                    anime.title,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                anime.finalScore?.let {
                    Surface(color = scoreColor(it), shape = RoundedCornerShape(12.dp)) {
                        Text("%.2f".format(it), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = FontWeight.Bold, color = Color.Black)
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
    modifier: Modifier = Modifier
) {
    val pathFlow = remember(anime.id) { coverRepository.observeLocalCoverPath(anime.id) }
    val immediatePath = remember(anime.id, anime.localCoverPath) {
        coverRepository.immediateLocalCoverPath(anime.id, anime.localCoverPath)
    }
    val localPath by pathFlow.collectAsState(initial = immediatePath)
    val downloadStateFlow = remember(anime.id) { coverRepository.observeDownloadState(anime.id) }
    val downloadState by downloadStateFlow.collectAsState()
    var bitmap by remember(anime.id, localPath) {
        mutableStateOf(localPath?.let { coverRepository.cachedBitmap(anime.id, it) })
    }

    LaunchedEffect(anime.id, localPath) {
        val currentLocalPath = localPath
        if (currentLocalPath != null && bitmap == null) {
            val loaded = coverRepository.loadBitmap(anime.id, currentLocalPath)
            if (loaded == null) {
                coverRepository.invalidateLocalCover(anime, currentLocalPath)
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
                    dispatchCoverTap(localPath, onDownloadRequested, onCoverClick)
                }
        )
        localPath == null -> DefaultCover(
            modifier = modifier,
            downloading = downloadState == CoverDownloadState.DOWNLOADING,
            onClick = { dispatchCoverTap(null, onDownloadRequested, onCoverClick) }
        )
        else -> Box(modifier.background(Color(0xFF1A1F2E)).clip(RoundedCornerShape(10.dp)))
    }
}

@Composable
private fun DefaultCover(modifier: Modifier = Modifier, downloading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(color = Color(0xFF1A1F2E))
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !downloading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Image, contentDescription = stringResource(R.string.cover_download), tint = Color.Gray, modifier = Modifier.size(22.dp))
        if (downloading) {
            CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp, color = Cyan)
        }
    }
}

@Composable
private fun CoverViewerDialog(
    anime: Anime,
    coverRepository: CoverRepository,
    onDismiss: () -> Unit,
    onDeleteConfirmed: ((Boolean) -> Unit) -> Unit
) {
    val pathFlow = remember(anime.id) { coverRepository.observeLocalCoverPath(anime.id) }
    val initialPath = remember(anime.id, anime.localCoverPath) {
        coverRepository.immediateLocalCoverPath(anime.id, anime.localCoverPath)
    }
    val localPath by pathFlow.collectAsState(initial = initialPath)
    var bitmap by remember(anime.id, localPath) {
        mutableStateOf(localPath?.let { coverRepository.cachedBitmap(anime.id, it) })
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    LaunchedEffect(anime.id, localPath) {
        val currentPath = localPath
        if (currentPath == null) {
            onDismiss()
        } else if (bitmap == null) {
            val loaded = coverRepository.loadBitmap(anime.id, currentPath)
            if (loaded == null) {
                coverRepository.invalidateLocalCover(anime, currentPath)
            } else {
                bitmap = loaded
            }
        }
    }

    Dialog(onDismissRequest = { if (!deleting) onDismiss() }) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
            color = Color(0xFF08090E),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(anime.title, modifier = Modifier.weight(1f), maxLines = 2, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, enabled = !deleting) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cover_close))
                    }
                }
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black),
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
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cover_delete))
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
    onSaveElaborate: (RatingDraft) -> Unit,
    onSaveSimple: (RatingDraft, Double) -> Unit,
    onDelete: () -> Unit
) {
    val config = settings.ratingConfig
    val elaborate = settings.elaborateRatingEnabled
    val defaultDraft = remember(anime.id, initial, config.categories, elaborate) {
        if (elaborate && initial.categoryScores.isEmpty()) initial.withDefaultCategoryScores(config) else initial
    }
    var draft by remember(anime.id, defaultDraft) { mutableStateOf(defaultDraft) }
    var simpleInput by remember(anime.id, initial.finalScore) {
        mutableStateOf(formatRatingInput(initial.finalScore))
    }
    BackHandler(onBack = onBack)
    val base = draft.baseScore(config)
    val calculatedFinal = draft.calculatedFinalScore(config)
    val parsedScore = parseSimpleRating(simpleInput)
    val stickyScore = settings.stickyScore

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text(anime.title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 36.dp)
        ) {
            if (elaborate) {
                if (stickyScore) {
                    stickyHeader { ScoreHeader(base, draft.additiveScore, calculatedFinal, config) }
                } else {
                    item { ScoreHeader(base, draft.additiveScore, calculatedFinal, config) }
                }
                items(config.categories.filter { it.active }.sortedBy { it.order }, key = { it.id }) { category ->
                    CategoryEditor(category, draft.categoryScores[category.id]) { newValue ->
                        draft = draft.copy(categoryScores = draft.categoryScores + (category.id to newValue))
                    }
                }
                config.additiveBonus?.let { bonus ->
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(R.string.bonus_range, localizedBonusName(bonus), bonus.maxValue),
                                    fontWeight = FontWeight.Bold,
                                    color = Magenta
                                )
                                Text(stringResource(R.string.bonus_explanation), color = Color.Gray, fontSize = 13.sp)
                                Slider(
                                    value = draft.additiveScore.toFloat(),
                                    onValueChange = { draft = draft.copy(additiveScore = (it * 10).roundToInt() / 10.0) },
                                    valueRange = 0f..bonus.maxValue.toFloat(),
                                    steps = (bonus.maxValue * 10).toInt() - 1
                                )
                                Text(stringResource(R.string.bonus_current, draft.additiveScore), fontWeight = FontWeight.Bold)
                            }
                        }
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
                            OutlinedTextField(
                                value = simpleInput,
                                onValueChange = { simpleInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.rating_title)) },
                                placeholder = { Text(stringResource(R.string.rating_simple_example)) },
                                singleLine = true,
                                isError = simpleInput.isNotBlank() && parsedScore == null,
                                supportingText = {
                                    if (simpleInput.isNotBlank() && parsedScore == null) {
                                        Text(stringResource(R.string.rating_simple_error))
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                        }
                    }
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
                            if (elaborate) onSaveElaborate(draft)
                            else parsedScore?.let { onSaveSimple(draft, it) }
                        },
                        enabled = elaborate || parsedScore != null,
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
}

@Composable
private fun ScoreHeader(base: Double?, additive: Double, final: Double?, config: RatingSystemConfig) {
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
                Surface(color = scoreColor(final ?: 0.0), shape = RoundedCornerShape(16.dp)) {
                    Text(final?.let { "%.2f".format(it) } ?: "—", modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), color = Color.Black, fontWeight = FontWeight.Black, fontSize = 24.sp)
                }
            }
        }
    }
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
                Slider(
                    value = score.toFloat(),
                    onValueChange = { onChange((it * 2).roundToInt() / 2.0) },
                    valueRange = 1f..10f,
                    steps = 17
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
private fun Top3Podium(
    top3: List<Anime>,
    coverRepository: CoverRepository,
    onCoverDownload: (Anime) -> Unit,
    onCoverOpen: (Anime) -> Unit,
    onAnime: (Anime) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        when (top3.size) {
            1 -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                PodiumItem(top3[0], 1, true, coverRepository, { onCoverDownload(top3[0]) }, { onCoverOpen(top3[0]) }, { onAnime(top3[0]) }, Modifier.width(170.dp))
            }
            2 -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom
            ) {
                PodiumItem(top3[1], 2, false, coverRepository, { onCoverDownload(top3[1]) }, { onCoverOpen(top3[1]) }, { onAnime(top3[1]) }, Modifier.weight(1f))
                PodiumItem(top3[0], 1, true, coverRepository, { onCoverDownload(top3[0]) }, { onCoverOpen(top3[0]) }, { onAnime(top3[0]) }, Modifier.weight(1.2f))
            }
            else -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom
            ) {
                PodiumItem(top3[1], 2, false, coverRepository, { onCoverDownload(top3[1]) }, { onCoverOpen(top3[1]) }, { onAnime(top3[1]) }, Modifier.weight(1f))
                PodiumItem(top3[0], 1, true, coverRepository, { onCoverDownload(top3[0]) }, { onCoverOpen(top3[0]) }, { onAnime(top3[0]) }, Modifier.weight(1.2f))
                PodiumItem(top3[2], 3, false, coverRepository, { onCoverDownload(top3[2]) }, { onCoverOpen(top3[2]) }, { onAnime(top3[2]) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PodiumItem(
    anime: Anime,
    position: Int,
    isLarge: Boolean,
    coverRepository: CoverRepository,
    onCoverDownload: () -> Unit,
    onCoverOpen: () -> Unit,
    onOpenRating: () -> Unit,
    modifier: Modifier = Modifier
) {
    val podiumRes = when (position) {
        1 -> R.drawable.podium_first
        2 -> R.drawable.podium_second
        else -> R.drawable.podium_third
    }
    val podiumHeight = if (isLarge) 170.dp * 0.66f else 130.dp * 0.66f
    val coverSize = if (isLarge) 120.dp else 96.dp
    val podiumDrop = 56.dp
    val scoreColorValue = scoreColor(anime.finalScore ?: 0.0)

    // Reserve the podium's downward extension inside the item to keep later rows clear.
    val totalHeight = podiumHeight + (coverSize / 2) + 24.dp + 18.dp + podiumDrop

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
    ) {
        Image(
            painter = painterResource(id = podiumRes),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(podiumHeight),
            contentScale = ContentScale.FillBounds
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 8.dp).zIndex(1f)
        ) {
            Surface(
                color = scoreColorValue,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.clickable { dispatchTop3ScoreTap(onOpenRating) }
            ) {
                Text(
                    "%.2f".format(anime.finalScore ?: 0.0),
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isLarge) 18.sp else 16.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            AnimeCover(
                anime,
                coverRepository,
                onCoverDownload,
                onCoverOpen,
                Modifier.size(coverSize).clip(RoundedCornerShape(12.dp)).zIndex(1f)
            )
        }
    }
}

@Composable
private fun SettingsScreen(settings: AppSettingsState, viewModel: RankOfflineViewModel) {
    var destination by remember { mutableStateOf(SettingsDestination.HOME) }
    val preloadProgress by viewModel.coverPreloadProgress.collectAsState()
    val downloadedCoverCount by viewModel.downloadedCoverCount.collectAsState()
    val goBack = { destination = SettingsDestination.HOME }

    BackHandler(enabled = destination != SettingsDestination.HOME, onBack = goBack)

    when (destination) {
        SettingsDestination.HOME -> SettingsHomeScreen(
            onGeneral = { destination = SettingsDestination.GENERAL },
            onRating = { destination = SettingsDestination.RATING },
            onIcon = { destination = SettingsDestination.ICON },
            onAbout = { destination = SettingsDestination.ABOUT }
        )
        SettingsDestination.GENERAL -> GeneralSettingsScreen(
            settings = settings,
            preloadProgress = preloadProgress,
            downloadedCoverCount = downloadedCoverCount,
            onStickyScoreChange = viewModel::updateStickyScore,
            onShowImagesChange = viewModel::updateShowImages,
            onLanguage = { destination = SettingsDestination.LANGUAGE },
            onDownloadPopular = viewModel::downloadPopularCovers,
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
            ratingConfig = settings.ratingConfig,
            elaborateRatingEnabled = settings.elaborateRatingEnabled,
            onElaborateRatingEnabledChange = viewModel::updateElaborateRatingEnabled,
            onUpdate = viewModel::updateRatingConfig,
            onReset = viewModel::resetRatingConfig,
            onBack = goBack
        )
        SettingsDestination.ICON -> IconSettingsScreen(
            selectedIcon = settings.appIcon,
            onIconSelected = viewModel::updateAppIcon,
            onBack = goBack
        )
        SettingsDestination.ABOUT -> LicenceAboutScreen(onBack = goBack)
    }
}

private enum class SettingsDestination {
    HOME,
    GENERAL,
    LANGUAGE,
    RATING,
    ICON,
    ABOUT
}

@Composable
private fun SettingsHomeScreen(
    onGeneral: () -> Unit,
    onRating: () -> Unit,
    onIcon: () -> Unit,
    onAbout: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.settings_title), fontSize = 28.sp, fontWeight = FontWeight.Bold)
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
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
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
    preloadProgress: CoverPreloadProgress,
    downloadedCoverCount: Int,
    onStickyScoreChange: (Boolean) -> Unit,
    onShowImagesChange: (Boolean) -> Unit,
    onLanguage: () -> Unit,
    onDownloadPopular: (PopularCoverBatch) -> Unit,
    onClearCovers: () -> Unit,
    onVisibilityChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showTop5000Confirmation by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onVisibilityChanged(true)
        onDispose { onVisibilityChanged(false) }
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
    if (showTop5000Confirmation) {
        AlertDialog(
            onDismissRequest = { showTop5000Confirmation = false },
            title = { Text(stringResource(R.string.covers_update_top_5000_title)) },
            text = {
                Text(
                    stringResource(R.string.covers_update_top_5000_warning)
                )
            },
            dismissButton = {
                TextButton(onClick = { showTop5000Confirmation = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTop5000Confirmation = false
                    onDownloadPopular(PopularCoverBatch.TOP_5000)
                }) { Text(stringResource(R.string.action_continue)) }
            }
        )
    }
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
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.covers_popularity_section), fontWeight = FontWeight.Bold, color = Mint)
                        Text(
                            stringResource(R.string.covers_popularity_description),
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            stringResource(R.string.covers_update_explanation),
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                        )
                        Text(
                            stringResource(R.string.covers_downloaded_count, downloadedCoverCount),
                            fontWeight = FontWeight.SemiBold,
                            color = Color.LightGray,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        PopularCoverBatch.entries.forEach { batch ->
                            Button(
                                onClick = {
                                    if (batch == PopularCoverBatch.TOP_5000) {
                                        showTop5000Confirmation = true
                                    } else {
                                        onDownloadPopular(batch)
                                    }
                                },
                                enabled = !preloadProgress.isActive,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.covers_update_top, batch.count))
                            }
                        }
                        if (preloadProgress.status != CoverPreloadStatus.IDLE) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                when (preloadProgress.status) {
                                    CoverPreloadStatus.COMPLETED -> stringResource(R.string.covers_update_completed, preloadProgress.target)
                                    CoverPreloadStatus.FAILED -> stringResource(R.string.covers_update_interrupted, preloadProgress.target)
                                    else -> stringResource(R.string.covers_updating, preloadProgress.target)
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(R.string.popularity_title),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                when {
                                    preloadProgress.status == CoverPreloadStatus.FETCHING_POPULARITY -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(
                                                R.string.popularity_updating_progress,
                                                preloadProgress.popularityProcessed,
                                                preloadProgress.target
                                            ),
                                            color = Color.LightGray
                                        )
                                    }
                                    preloadProgress.status == CoverPreloadStatus.APPLYING_POPULARITY -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.popularity_applying), color = Color.LightGray)
                                    }
                                    preloadProgress.popularityUpdated -> {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Mint)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.popularity_updated), color = Color.LightGray)
                                    }
                                    preloadProgress.status == CoverPreloadStatus.FAILED -> {
                                        Icon(Icons.Default.Error, contentDescription = null, tint = Coral)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.popularity_update_failed), color = Coral)
                                    }
                                }
                            }
                            if (preloadProgress.status == CoverPreloadStatus.FETCHING_POPULARITY) {
                                LinearProgressIndicator(
                                    progress = { preloadProgress.popularityFraction },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                            }
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
                                    R.string.covers_progress_details,
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
                                preloadProgress.status == CoverPreloadStatus.FAILED &&
                                    preloadProgress.popularityUpdated -> Text(
                                    stringResource(R.string.covers_download_interrupted),
                                    color = Coral,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
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
}

@Composable
private fun RatingSettingsScreen(
    ratingConfig: RatingSystemConfig,
    elaborateRatingEnabled: Boolean,
    onElaborateRatingEnabledChange: (Boolean) -> Unit,
    onUpdate: (RatingSystemConfig) -> Unit,
    onReset: () -> Unit,
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
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.rating_elaborate), fontWeight = FontWeight.Bold, color = Magenta)
                            Text(
                                stringResource(R.string.rating_elaborate_description),
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                        }
                        Switch(
                            checked = elaborateRatingEnabled,
                            onCheckedChange = onElaborateRatingEnabledChange
                        )
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.rating_system), fontWeight = FontWeight.Bold, color = Magenta)
                        val activeCategories = ratingConfig.categories.filter { it.active }.sortedBy { it.order }
                        activeCategories.forEach { category ->
                            RatingCategoryEditor(
                                category = category,
                                onUpdate = { newCategory ->
                                    val updated = ratingConfig.categories.map {
                                        if (it.id == newCategory.id) newCategory else it
                                    }
                                    onUpdate(ratingConfig.copy(categories = updated.sortedBy { it.order }))
                                },
                                onDelete = {
                                    if (activeCategories.size > 1) {
                                        val updated = ratingConfig.categories.map {
                                            if (it.id == category.id) it.copy(active = false) else it
                                        }
                                        onUpdate(ratingConfig.copy(categories = updated))
                                    }
                                }
                            )
                        }
                        val inactiveCategories = ratingConfig.categories.filterNot { it.active }.sortedBy { it.order }
                        if (inactiveCategories.isNotEmpty()) {
                            Text(stringResource(R.string.rating_inactive_categories), color = Color.Gray, modifier = Modifier.padding(top = 12.dp))
                            inactiveCategories.forEach { category ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(localizedCategoryName(category), modifier = Modifier.weight(1f), color = Color.LightGray)
                                    TextButton(onClick = {
                                        val updated = ratingConfig.categories.map {
                                            if (it.id == category.id) it.copy(active = true) else it
                                        }
                                        onUpdate(ratingConfig.copy(categories = updated))
                                    }) { Text(stringResource(R.string.action_restore)) }
                                }
                            }
                        }
                        val newCategoryName = stringResource(R.string.rating_new_category_name)
                        val newCategoryDescription = stringResource(R.string.rating_new_category_description)
                        Button(
                            onClick = {
                                val nextOrder = ratingConfig.categories.maxOfOrNull { it.order }?.plus(1) ?: 0
                                val newCategory = RatingCategory(
                                    id = "custom_${UUID.randomUUID()}",
                                    name = newCategoryName,
                                    description = newCategoryDescription,
                                    weight = 10.0,
                                    color = "#7EC7FF",
                                    order = nextOrder
                                )
                                onUpdate(ratingConfig.copy(categories = ratingConfig.categories + newCategory))
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        ) {
                            Text(stringResource(R.string.rating_add_category))
                        }
                        Button(
                            onClick = onReset,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        ) {
                            Text(stringResource(R.string.rating_reset_defaults))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenceAboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember(context.packageName) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    SettingsSubscreen(stringResource(R.string.settings_about_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("RankOffline", fontWeight = FontWeight.Bold, color = Mint)
                        Text(
                            stringResource(R.string.about_version, versionName),
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            stringResource(R.string.about_summary),
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.about_licences), fontWeight = FontWeight.Bold, color = Cyan)
                        Text(stringResource(R.string.about_rankoffline_licence), color = Color.LightGray, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            stringResource(R.string.about_catalog_licence),
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            stringResource(R.string.about_android_licence),
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.about_acknowledgements), fontWeight = FontWeight.Bold, color = Magenta)
                        Text(
                            stringResource(R.string.about_acknowledgements_text),
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingCategoryEditor(category: RatingCategory, onUpdate: (RatingCategory) -> Unit, onDelete: () -> Unit) {
    val accent = try {
        Color(android.graphics.Color.parseColor(category.color))
    } catch (_: Exception) {
        Cyan
    }
    val swatch = try {
        Color(android.graphics.Color.parseColor(category.color))
    } catch (_: Exception) {
        Color.White
    }

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
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red)
                }
            }
            OutlinedTextField(
                value = localizedCategoryDescription(category),
                onValueChange = { onUpdate(category.copy(description = it)) },
                label = { Text(stringResource(R.string.category_description)) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(stringResource(R.string.category_weight), color = accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                Slider(
                    value = category.weight.toFloat().coerceIn(0f, 100f),
                    onValueChange = { onUpdate(category.copy(weight = it.toDouble())) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f)
                )
                Text("${category.weight.toInt()}%", modifier = Modifier.width(54.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.category_color), color = Color.LightGray, modifier = Modifier.width(52.dp))
                Box(
                    modifier = Modifier.size(28.dp).background(swatch, RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        val nextColor = if (listOf("#19D5E5", "#E04BCF", "#FF675D", "#FFAA55", "#62D6C8", "#7EC7FF").contains(category.color)) {
                            "#7EC7FF"
                        } else {
                            "#19D5E5"
                        }
                        onUpdate(category.copy(color = nextColor))
                    }
                ) { Text(stringResource(R.string.category_tint)) }
            }
        }
    }
}

@Composable
private fun CatalogSortOrder.shortLabel(): String = when (this) {
    CatalogSortOrder.POPULARITY -> stringResource(R.string.sort_most_popular)
    CatalogSortOrder.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    CatalogSortOrder.TITLE_DESC -> stringResource(R.string.sort_title_descending)
}

@Composable
private fun CatalogSortOrder.menuLabel(): String = when (this) {
    CatalogSortOrder.POPULARITY -> stringResource(R.string.sort_most_popular)
    CatalogSortOrder.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    CatalogSortOrder.TITLE_DESC -> stringResource(R.string.sort_title_descending)
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
    val canonical = DefaultSettings.defaultRatingCategories.firstOrNull { it.id == category.id }
    val resource = if (canonical?.name == category.name) categoryNameResource(category.id) else null
    return resource?.let { stringResource(it) } ?: category.name
}

@Composable
private fun localizedCategoryDescription(category: RatingCategory): String {
    val canonical = DefaultSettings.defaultRatingCategories.firstOrNull { it.id == category.id }
    val resource = if (canonical?.description == category.description) categoryDescriptionResource(category.id) else null
    return resource?.let { stringResource(it) } ?: category.description
}

@Composable
private fun localizedBonusName(bonus: AdditiveBonus): String =
    if (bonus.name == DefaultSettings.defaultAdditiveBonus.name) {
        stringResource(R.string.bonus_personal_taste)
    } else {
        bonus.name
    }

private fun categoryNameResource(id: String): Int? = when (id) {
    "writing" -> R.string.category_writing
    "characters" -> R.string.category_characters
    "engagement" -> R.string.category_engagement
    "visuals" -> R.string.category_visuals
    "worldbuilding" -> R.string.category_worldbuilding
    else -> null
}

private fun categoryDescriptionResource(id: String): Int? = when (id) {
    "writing" -> R.string.category_writing_description
    "characters" -> R.string.category_characters_description
    "engagement" -> R.string.category_engagement_description
    "visuals" -> R.string.category_visuals_description
    "worldbuilding" -> R.string.category_worldbuilding_description
    else -> null
}

private fun scoreColor(v: Double): Color = when {
    v >= 9 -> Cyan
    v >= 8 -> Mint
    v >= 7 -> Color(0xFFA4CD78)
    v >= 5 -> Orange
    v >= 3 -> Coral
    else -> Color(0xFFF32B60)
}
