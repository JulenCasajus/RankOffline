package com.animerank.offline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Bg = Color(0xFF0A0B12)
private val Panel = Color(0xFF121522)
private val Cyan = Color(0xFF19D5E5)
private val Magenta = Color(0xFFE04BCF)
private val Coral = Color(0xFFFF675D)
private val Orange = Color(0xFFFFAA55)
private val Mint = Color(0xFF62D6C8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AnimeDatabase(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Cyan,
                    secondary = Magenta,
                    background = Bg,
                    surface = Panel
                )
            ) {
                AnimeRankApp(db)
            }
        }
    }
}

@Composable
fun AnimeRankApp(db: AnimeDatabase) {
    var selected by remember { mutableStateOf<Anime?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }

    selected?.let { anime ->
        RatingScreen(
            anime = anime,
            initial = db.loadRating(anime.id),
            onBack = { selected = null },
            onSave = { draft -> db.saveRating(anime.id, draft); refresh++; selected = null },
            onDelete = { db.deleteRating(anime.id); refresh++; selected = null }
        )
        return
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Search, null) }, label = { Text("Anime") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.EmojiEvents, null) }, label = { Text("Ranking") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Info, null) }, label = { Text("Sistema") })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> AnimeListScreen(db, false, refresh, onAnime = { selected = it })
                1 -> AnimeListScreen(db, true, refresh, onAnime = { selected = it })
                else -> PhilosophyScreen(db.animeCount())
            }
        }
    }
}

@Composable
private fun AnimeListScreen(db: AnimeDatabase, rankedOnly: Boolean, refresh: Int, onAnime: (Anime) -> Unit) {
    var query by remember { mutableStateOf("") }
    val list = remember(query, rankedOnly, refresh) { db.searchAnime(query, rankedOnly) }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Text(if (rankedOnly) "Mi ranking" else "AnimeRank Offline", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(if (rankedOnly) "Ordenado automáticamente por tu puntuación final." else "Catálogo local: ${db.animeCount()} entradas. Funciona sin conexión.", color = Color.LightGray)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Buscar anime…") }
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { anime ->
                AnimeRow(anime, onClick = { onAnime(anime) })
            }
            if (list.isEmpty()) item { Text("No hay resultados.", color = Color.Gray, modifier = Modifier.padding(20.dp)) }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(anime.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                val meta = buildList {
                    anime.status?.let { add(it) }
                    anime.baseScore?.let { add("Calidad %.2f".format(it)) }
                    anime.personalTaste?.let { if (it > 0) add("Gusto +%.1f".format(it)) }
                }.joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, color = Color.Gray, fontSize = 13.sp)
            }
            anime.finalScore?.let {
                Surface(color = scoreColor(it), shape = RoundedCornerShape(12.dp)) {
                    Text("%.2f".format(it), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RatingScreen(anime: Anime, initial: RatingDraft, onBack: () -> Unit, onSave: (RatingDraft) -> Unit, onDelete: () -> Unit) {
    var draft by remember(anime.id) { mutableStateOf(initial) }
    BackHandler(onBack = onBack)
    val base = draft.baseScore()
    val final = draft.finalScore()

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text(anime.title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 36.dp)
        ) {
            item {
                ScoreHeader(base, draft.personalTaste, final)
            }
            items(categorySpecs) { spec ->
                CategoryEditor(spec, draft.categoryScores[spec.key]) { newValue ->
                    draft = draft.copy(categoryScores = draft.categoryScores + (spec.key to newValue))
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Gusto personal · +0 a +1", fontWeight = FontWeight.Bold, color = Magenta)
                        Text("Se suma después de calcular la calidad. Nunca baja la nota.", color = Color.Gray, fontSize = 13.sp)
                        Slider(
                            value = draft.personalTaste.toFloat(),
                            onValueChange = { draft = draft.copy(personalTaste = (it * 10).roundToInt() / 10.0) },
                            valueRange = 0f..1f,
                            steps = 9
                        )
                        Text("+%.1f".format(draft.personalTaste), fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                StatusEditor(draft.status) { draft = draft.copy(status = it) }
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
                    Button(onClick = { onSave(draft) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Guardar") }
                    if (anime.finalScore != null) {
                        OutlinedButton(onClick = onDelete) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text("Borrar") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreHeader(base: Double?, taste: Double, final: Double?) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text("Puntuación", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Calidad: ${base?.let { "%.2f".format(it) } ?: "—"}", color = Color.LightGray)
                    Text("Gusto: +%.1f".format(taste), color = Magenta)
                }
                Surface(color = scoreColor(final ?: 0.0), shape = RoundedCornerShape(16.dp)) {
                    Text(final?.let { "%.2f".format(it) } ?: "—", modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), color = Color.Black, fontWeight = FontWeight.Black, fontSize = 24.sp)
                }
            }
        }
    }
}

@Composable
private fun CategoryEditor(spec: CategorySpec, score: Double?, onChange: (Double?) -> Unit) {
    val accent = when (spec.key) {
        "writing" -> Cyan
        "characters" -> Magenta
        "engagement" -> Coral
        "visuals" -> Orange
        else -> Mint
    }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${spec.title} · ${(spec.weight * 100).toInt()}%", fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(if (score == null) 5.0 else null) }) { Text(if (score == null) "Activar" else "N/A") }
            }
            Text(spec.hints.joinToString(" · "), color = Color.Gray, fontSize = 13.sp)
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
private fun StatusEditor(status: String, onChange: (String) -> Unit) {
    val options = listOf("Plan to watch", "Watching", "Completed", "Dropped")
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Estado", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { s ->
                    FilterChip(selected = status == s, onClick = { onChange(s) }, label = { Text(s) })
                }
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
