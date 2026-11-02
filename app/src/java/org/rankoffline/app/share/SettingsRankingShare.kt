package org.rankoffline.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Canonical sharing UI: two swipable formats, one renderer, any number of arbitrary packs. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsRankingShareScreen(
    viewModel: RankOfflineViewModel,
    manifests: List<PackManifest>,
    onBack: () -> Unit
) {
    val data by viewModel.rankingShareData.collectAsState()
    val export by viewModel.rankingShareExport.collectAsState()
    val uri by viewModel.pendingRankingShare.collectAsState()
    val selectedSettings by viewModel.settings.collectAsState()
    var showImages by remember(data.scope) {
        mutableStateOf(if (data.scope == ViewingScope.All) selectedSettings.allViewSettings.showImages
            else selectedSettings.mediaSettings[(data.scope as? ViewingScope.Pack)?.packId]?.showImages ?: true)
    }
    val pager = rememberPagerState(pageCount = { RankingShareTemplate.entries.size })
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val scopeName = when (val current = data.scope) {
        ViewingScope.All -> stringResource(R.string.media_all)
        is ViewingScope.Pack -> manifests.firstOrNull { it.packId == current.packId }?.displayName
            ?: current.packId.value.toString()
    }
    val selectedTemplate = RankingShareTemplate.entries[pager.currentPage]
    fun makeRequest(template: RankingShareTemplate) = RankingShareRenderRequest(
        heading = "My Top ${template.limit} $scopeName",
        items = data.items.take(template.limit), template = template,
        showImages = showImages, accentArgb = accent,
        backgroundPath = if (template == RankingShareTemplate.TOP_SEVEN) data.topSevenBackground else data.topTenBackground
    )

    SharedSettingsUri(uri, "image/png", "RankOffline ranking") { viewModel.rankingShareHandled() }
    SettingsSubscreen(stringResource(R.string.ranking_share_title), onBack) {
        val bottomClearance = LocalFloatingNavigationClearance.current
        Column(
            Modifier.fillMaxSize().padding(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = 16.dp + bottomClearance
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ranking_share_show_images), Modifier.weight(1f))
                Switch(showImages, onCheckedChange = { showImages = it })
            }
            // Scope selector works with arbitrary PackIds; no Anime/MediaType special cases.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = data.scope == ViewingScope.All,
                        onClick = { viewModel.loadRankingShare(ViewingScope.All) },
                        label = { Text(stringResource(R.string.media_all)) })
                }
                items(manifests, key = { it.packId.value }) { pack ->
                    FilterChip(selected = data.scope == ViewingScope.Pack(pack.packId),
                        onClick = { viewModel.loadRankingShare(ViewingScope.Pack(pack.packId)) },
                        label = { Text(pack.displayName) })
                }
            }
            when {
                data.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                data.failed -> Text(stringResource(R.string.ranking_share_failed))
                data.items.isEmpty() -> Text(stringResource(R.string.ranking_share_empty))
            }
            HorizontalPager(
                state = pager, modifier = Modifier.weight(1f).fillMaxWidth(),
                pageSpacing = 16.dp, verticalAlignment = Alignment.CenterVertically
            ) { index ->
                val template = RankingShareTemplate.entries[index]
                val request = remember(data, showImages, accent, template, scopeName) { makeRequest(template) }
                ShareTemplatePreview(request, Modifier.fillMaxSize())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                RankingShareTemplate.entries.forEachIndexed { index, template ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 14.dp)) {
                        Box(Modifier.size(if (pager.currentPage == index) 10.dp else 8.dp)
                            .background(if (pager.currentPage == index) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline, CircleShape))
                        Text(if (template == RankingShareTemplate.TOP_SEVEN) "Top 7" else "Top 10",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Button(onClick = { viewModel.exportRankingShare(makeRequest(selectedTemplate)) },
                enabled = data.items.isNotEmpty() && !data.loading && !data.failed && !export.rendering,
                modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_continue))
            }
        }
    }
}

@Composable
private fun ShareTemplatePreview(request: RankingShareRenderRequest, modifier: Modifier = Modifier) {
    val width = if (request.template == RankingShareTemplate.TOP_SEVEN) 360 else 640
    val height = if (request.template == RankingShareTemplate.TOP_SEVEN) 640 else 360
    val image by produceState<Bitmap?>(initialValue = null, request) {
        value = try { withContext(Dispatchers.Default) {
            RankingShareCardRenderer.render(request, width, height)
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    DisposableEffect(image) {
        val current = image
        onDispose { current?.recycle() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        image?.let {
            Image(bitmap = it.asImageBitmap(), contentDescription = "Ranking preview",
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } ?: CircularProgressIndicator()
    }
}
