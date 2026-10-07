package com.animerank.offline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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

class MainActivity : ComponentActivity() {
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
                AnimeRankApp(appViewModel)
            }
        }
    }
}

@Composable
fun AnimeRankApp(viewModel: RankOfflineViewModel) {
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

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
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
                onSave = { draft -> viewModel.saveRating(editor.anime.id, draft) },
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
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Search, null) }, label = { Text("Anime") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.EmojiEvents, null) }, label = { Text("Ranking") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
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
    errorMessage: String?
) {
    val listState = rememberLazyListState()
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val formattedCatalogCount = remember(catalogCount) {
        NumberFormat.getIntegerInstance(Locale("es", "ES")).format(catalogCount)
    }

    LaunchedEffect(query, sortOrder) {
        if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
            listState.scrollToItem(0)
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Text("AnimeRank Offline", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Catálogo local: $formattedCatalogCount entradas.",
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
        errorMessage?.let { Text(it, color = Coral, fontSize = 13.sp) }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Buscar anime…") }
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
            if (list.isEmpty()) item { Text("No hay resultados.", color = Color.Gray, modifier = Modifier.padding(20.dp)) }
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
            Text("Ranking", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(if (imagesEnabled) "Top 3 y lista completa por puntuación." else "Lista completa por puntuación.", color = Color.LightGray)
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
                item { Text("Todavía no hay valoraciones guardadas.", color = Color.Gray, modifier = Modifier.padding(20.dp)) }
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
                item { Text("Todavía no hay valoraciones guardadas.", color = Color.Gray, modifier = Modifier.padding(20.dp)) }
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
        Icon(Icons.Default.Image, contentDescription = "Descargar portada", tint = Color.Gray, modifier = Modifier.size(22.dp))
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
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
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
                    Text("Eliminar portada")
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text("¿Eliminar esta portada?") },
            text = { Text("Podrás volver a descargarla cuando quieras.") },
            dismissButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = { confirmDelete = false }
                ) { Text("Cancelar") }
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
                ) { Text("Eliminar", color = Coral) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RatingScreen(anime: Anime, initial: RatingDraft, settings: AppSettingsState, onBack: () -> Unit, onSave: (RatingDraft) -> Unit, onDelete: () -> Unit) {
    val config = settings.ratingConfig
    val defaultDraft = remember(anime.id, initial, config.categories) {
        if (initial.categoryScores.isEmpty()) initial.withDefaultCategoryScores(config) else initial
    }
    var draft by remember(anime.id, defaultDraft) { mutableStateOf(defaultDraft) }
    BackHandler(onBack = onBack)
    val base = draft.baseScore(config)
    val final = draft.finalScore(config)
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
            if (stickyScore) {
                stickyHeader { ScoreHeader(base, draft.additiveScore, final, config) }
            } else {
                item { ScoreHeader(base, draft.additiveScore, final, config) }
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
                            Text("${bonus.name} · +0 a +${bonus.maxValue}", fontWeight = FontWeight.Bold, color = Magenta)
                            Text("Se suma después de calcular la calidad. Nunca baja la nota.", color = Color.Gray, fontSize = 13.sp)
                            Slider(
                                value = draft.additiveScore.toFloat(),
                                onValueChange = { draft = draft.copy(additiveScore = (it * 10).roundToInt() / 10.0) },
                                valueRange = 0f..bonus.maxValue.toFloat(),
                                steps = (bonus.maxValue * 10).toInt() - 1
                            )
                            Text("+%.1f".format(draft.additiveScore), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = draft.notes,
                    onValueChange = { draft = draft.copy(notes = it) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = { Text("Notas") },
                    placeholder = { Text("Qué funcionó, qué falló, escenas memorables…") }
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { onSave(draft) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Save, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Guardar")
                    }
                    if (anime.finalScore != null) {
                        OutlinedButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Borrar")
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
            Text("Puntuación", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Calidad: ${base?.let { "%.2f".format(it) } ?: "—"}", color = Color.LightGray)
                    if (config.additiveBonus != null) {
                        Text("${config.additiveBonus.name}: +%.1f".format(additive), color = Magenta)
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
                Text("${category.name} · ${category.weight.toInt()}%", fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(if (score == null) 5.0 else null) }) { Text(if (score == null) "Activar" else "N/A") }
            }
            Text(category.description, color = Color.Gray, fontSize = 13.sp)
            if (score != null) {
                Slider(
                    value = score.toFloat(),
                    onValueChange = { onChange((it * 2).roundToInt() / 2.0) },
                    valueRange = 1f..10f,
                    steps = 17
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(scoreLabel(score), color = Color.LightGray, fontSize = 13.sp)
                    Text("%.1f / 10".format(score), fontWeight = FontWeight.Bold)
                }
            } else {
                Text("No aplica: su peso se redistribuye entre las demás categorías.", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
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
            onAbout = { destination = SettingsDestination.ABOUT }
        )
        SettingsDestination.GENERAL -> GeneralSettingsScreen(
            settings = settings,
            preloadProgress = preloadProgress,
            downloadedCoverCount = downloadedCoverCount,
            onStickyScoreChange = viewModel::updateStickyScore,
            onShowImagesChange = viewModel::updateShowImages,
            onDownloadPopular = viewModel::downloadPopularCovers,
            onClearCovers = viewModel::clearDownloadedCovers,
            onVisibilityChanged = viewModel::setGeneralSettingsVisible,
            onBack = goBack
        )
        SettingsDestination.RATING -> RatingSettingsScreen(
            ratingConfig = settings.ratingConfig,
            onUpdate = viewModel::updateRatingConfig,
            onReset = viewModel::resetSettings,
            onBack = goBack
        )
        SettingsDestination.ABOUT -> LicenceAboutScreen(onBack = goBack)
    }
}

private enum class SettingsDestination {
    HOME,
    GENERAL,
    RATING,
    ABOUT
}

@Composable
private fun SettingsHomeScreen(
    onGeneral: () -> Unit,
    onRating: () -> Unit,
    onAbout: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        SettingsMenuItem(
            icon = Icons.Default.Settings,
            title = "General settings",
            description = "Display and behavior",
            onClick = onGeneral
        )
        SettingsMenuItem(
            icon = Icons.Default.Star,
            title = "Rating settings",
            description = "Categories and scoring system",
            onClick = onRating
        )
        SettingsMenuItem(
            icon = Icons.Default.Info,
            title = "Licence / About",
            description = "App information, credits and licences",
            onClick = onAbout
        )
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
            Icon(Icons.Default.ChevronRight, contentDescription = "Open $title", tint = Color.LightGray)
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
private fun GeneralSettingsScreen(
    settings: AppSettingsState,
    preloadProgress: CoverPreloadProgress,
    downloadedCoverCount: Int,
    onStickyScoreChange: (Boolean) -> Unit,
    onShowImagesChange: (Boolean) -> Unit,
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
            title = { Text("¿Borrar las portadas descargadas?") },
            text = { Text("Las portadas podrán volver a descargarse cuando sea necesario.") },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("Cancelar") }
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirmation = false
                    onClearCovers()
                }) { Text("Borrar", color = Coral) }
            }
        )
    }
    if (showTop5000Confirmation) {
        AlertDialog(
            onDismissRequest = { showTop5000Confirmation = false },
            title = { Text("Actualizar Top 5000") },
            text = {
                Text(
                    "Esta operación puede descargar muchas portadas y utilizar una cantidad " +
                        "considerable de datos y almacenamiento."
                )
            },
            dismissButton = {
                TextButton(onClick = { showTop5000Confirmation = false }) { Text("Cancelar") }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTop5000Confirmation = false
                    onDownloadPopular(PopularCoverBatch.TOP_5000)
                }) { Text("Continuar") }
            }
        )
    }
    SettingsSubscreen("General settings", onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Display", fontWeight = FontWeight.Bold, color = Cyan)
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Sticky Score Header", fontWeight = FontWeight.SemiBold)
                                Text("Keep score visible while scrolling", fontSize = 13.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = settings.stickyScore,
                                onCheckedChange = onStickyScoreChange
                            )
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Show Images", fontWeight = FontWeight.SemiBold)
                                Text("Display anime poster thumbnails", fontSize = 13.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = settings.showImages,
                                onCheckedChange = onShowImagesChange
                            )
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Popularidad y portadas", fontWeight = FontWeight.Bold, color = Mint)
                        Text(
                            "Actualiza los anime más populares y descarga sus portadas para tenerlas " +
                                "disponibles sin conexión.",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            "Cada actualización renueva la popularidad y descarga las portadas que falten. " +
                                "Las portadas individuales también pueden descargarse pulsando su placeholder.",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                        )
                        Text(
                            "Portadas descargadas: $downloadedCoverCount",
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
                                Text("Actualizar Top ${batch.count}")
                            }
                        }
                        if (preloadProgress.status != CoverPreloadStatus.IDLE) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                when (preloadProgress.status) {
                                    CoverPreloadStatus.COMPLETED -> "Top ${preloadProgress.target} actualizado"
                                    CoverPreloadStatus.FAILED ->
                                        "Actualización Top ${preloadProgress.target} interrumpida"
                                    else -> "Actualizando Top ${preloadProgress.target}"
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Popularidad",
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
                                            "Actualizando ranking… " +
                                                "${preloadProgress.popularityProcessed} / ${preloadProgress.target}",
                                            color = Color.LightGray
                                        )
                                    }
                                    preloadProgress.status == CoverPreloadStatus.APPLYING_POPULARITY -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Aplicando ranking…", color = Color.LightGray)
                                    }
                                    preloadProgress.popularityUpdated -> {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Mint)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Popularidad actualizada", color = Color.LightGray)
                                    }
                                    preloadProgress.status == CoverPreloadStatus.FAILED -> {
                                        Icon(Icons.Default.Error, contentDescription = null, tint = Coral)
                                        Spacer(Modifier.width(8.dp))
                                        Text("No se pudo actualizar", color = Coral)
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
                                "Portadas: ${preloadProgress.processed} / ${preloadProgress.target}",
                                color = Color.LightGray,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                            LinearProgressIndicator(
                                progress = { preloadProgress.fraction },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                            Text(
                                "${preloadProgress.existing} ya existentes · " +
                                    "${preloadProgress.downloaded} descargadas · ${preloadProgress.errors} errores",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            when {
                                preloadProgress.status == CoverPreloadStatus.COMPLETED -> Text(
                                    "✓ Portadas completadas",
                                    color = Mint,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                                preloadProgress.status == CoverPreloadStatus.FAILED &&
                                    preloadProgress.popularityUpdated -> Text(
                                    "Descarga de portadas interrumpida",
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
                            Text("Borrar portadas descargadas")
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
    onUpdate: (RatingSystemConfig) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen("Rating settings", onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Rating System", fontWeight = FontWeight.Bold, color = Magenta)
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
                            Text("Inactive categories", color = Color.Gray, modifier = Modifier.padding(top = 12.dp))
                            inactiveCategories.forEach { category ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(category.name, modifier = Modifier.weight(1f), color = Color.LightGray)
                                    TextButton(onClick = {
                                        val updated = ratingConfig.categories.map {
                                            if (it.id == category.id) it.copy(active = true) else it
                                        }
                                        onUpdate(ratingConfig.copy(categories = updated))
                                    }) { Text("Restore") }
                                }
                            }
                        }
                        Button(
                            onClick = {
                                val nextOrder = ratingConfig.categories.maxOfOrNull { it.order }?.plus(1) ?: 0
                                val newCategory = RatingCategory(
                                    id = "custom_${UUID.randomUUID()}",
                                    name = "New Category",
                                    description = "Your custom evaluation criteria.",
                                    weight = 10.0,
                                    color = "#7EC7FF",
                                    order = nextOrder
                                )
                                onUpdate(ratingConfig.copy(categories = ratingConfig.categories + newCategory))
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        ) {
                            Text("Add Category")
                        }
                        Button(
                            onClick = onReset,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        ) {
                            Text("Reset to Defaults")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenceAboutScreen(onBack: () -> Unit) {
    SettingsSubscreen("Licence / About", onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("RankOffline", fontWeight = FontWeight.Bold, color = Mint)
                        Text("Version 0.1.0", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            "An offline anime catalog and rating app. Your ratings and preferences are stored locally.",
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
                        Text("Licences", fontWeight = FontWeight.Bold, color = Cyan)
                        Text("RankOffline: MIT License.", color = Color.LightGray, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            "Anime catalog: AnimeAPI and upstream data sources; AnimeAPI reports ODbL 1.0 and DbCL 1.0, with some source components under other compatible licences.",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            "AndroidX, Jetpack Compose, DataStore and Kotlinx Serialization: Apache License 2.0.",
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
                        Text("Acknowledgements", fontWeight = FontWeight.Bold, color = Magenta)
                        Text(
                            "AnimeAPI and its upstream contributors provide catalog mappings. AniList and Jikan provide anime cover metadata when a cover is not already available locally.",
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
                    value = category.name,
                    onValueChange = { onUpdate(category.copy(name = it)) },
                    label = { Text("Name") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red)
                }
            }
            OutlinedTextField(
                value = category.description,
                onValueChange = { onUpdate(category.copy(description = it)) },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text("Weight", color = accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                Slider(
                    value = category.weight.toFloat().coerceIn(0f, 100f),
                    onValueChange = { onUpdate(category.copy(weight = it.toDouble())) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f)
                )
                Text("${category.weight.toInt()}%", modifier = Modifier.width(54.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Color", color = Color.LightGray, modifier = Modifier.width(52.dp))
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
                ) { Text("Tint") }
            }
        }
    }
}

@Composable
private fun PhilosophyScreen(catalogSize: Int) {
    LazyColumn(Modifier.fillMaxSize().background(Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Sistema de valoración", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Calidad primero; gusto personal separado.", color = Color.LightGray)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Fórmula", fontWeight = FontWeight.Bold, color = Cyan)
                    Text("Base = Writing×35% + Characters×25% + Engagement×20% + Visuals×15% + Worldbuilding×5%.")
                    Spacer(Modifier.height(6.dp))
                    Text("Final = min(10, Base + gusto personal), con gusto entre +0.0 y +1.0.")
                    Spacer(Modifier.height(6.dp))
                    Text("Si una categoría no aplica, se marca N/A y el resto de pesos se normaliza.", color = Color.LightGray)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Escala", fontWeight = FontWeight.Bold, color = Magenta)
                    listOf("10 — Flawless", "9 — Amazing", "8 — Great", "7 — Good", "6 — Decent", "5 — Average", "4 — Subpar", "3 — Poor", "2 — Bad", "1 — Unwatchable").forEach { Text(it) }
                }
            }
        }
        item {
            Text("Catálogo cargado: $catalogSize animes", color = Color.Gray)
        }
    }
}

private fun CatalogSortOrder.shortLabel(): String = when (this) {
    CatalogSortOrder.POPULARITY -> "Más populares"
    CatalogSortOrder.TITLE_ASC -> "A → Z"
    CatalogSortOrder.TITLE_DESC -> "Z → A"
}

private fun CatalogSortOrder.menuLabel(): String = when (this) {
    CatalogSortOrder.POPULARITY -> "Más populares"
    CatalogSortOrder.TITLE_ASC -> "A → Z"
    CatalogSortOrder.TITLE_DESC -> "Z → A"
}

private fun scoreLabel(v: Double): String = when {
    v >= 9.75 -> "Flawless"
    v >= 8.75 -> "Amazing"
    v >= 7.75 -> "Great"
    v >= 6.75 -> "Good"
    v >= 5.75 -> "Decent"
    v >= 4.75 -> "Average"
    v >= 3.75 -> "Subpar"
    v >= 2.75 -> "Poor"
    v >= 1.75 -> "Bad"
    else -> "Unwatchable"
}

private fun scoreColor(v: Double): Color = when {
    v >= 9 -> Cyan
    v >= 8 -> Mint
    v >= 7 -> Color(0xFFA4CD78)
    v >= 5 -> Orange
    v >= 3 -> Coral
    else -> Color(0xFFF32B60)
}
