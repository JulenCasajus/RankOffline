package org.rankoffline.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun RankingShareScreen(
    data: RankingShareData,
    readyMediaTypes: Set<MediaType>,
    allAvailable: Boolean,
    initialShowImages: (ViewingScope) -> Boolean,
    exporting: Boolean,
    onScopeSelected: (ViewingScope) -> Unit,
    scopeSelectionEnabled: Boolean = true,
    onContinue: (RankingShareRenderRequest) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val scopes = remember(readyMediaTypes, allAvailable) {
        buildList {
            if (allAvailable) add(ViewingScope.All)
            readyMediaTypes.sortedBy(MediaType::persistedValue).forEach { add(ViewingScope.Pack(it)) }
        }
    }
    var showImages by remember(data.scope) { mutableStateOf(initialShowImages(data.scope)) }
    val pagerState = rememberPagerState(pageCount = { RankingShareTemplate.entries.size })
    val scopeLabel = shareScopeLabel(data.scope)
    val accentArgb = shareAccent(data.scope)
    LaunchedEffect(scopes, data.scope) {
        if (scopes.isNotEmpty() && data.scope !in scopes) onScopeSelected(scopes.first())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ranking_share_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (scopeSelectionEnabled && scopes.size > 1) {
                RankOfflineSelector(
                    selectedValue = data.scope,
                    values = scopes,
                    title = stringResource(R.string.category_selector_title),
                    icon = Icons.Default.Share,
                    valueLabel = { shareScopeLabel(it) },
                    onValueSelected = onScopeSelected,
                    minHeight = 52.dp
                )
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ranking_share_show_images), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.ranking_share_show_images_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = showImages,
                        onCheckedChange = { showImages = it },
                        modifier = Modifier.testTag("ranking_share_show_images")
                    )
                }
            }

            when {
                data.loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                data.failed -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.ranking_share_failed), color = MaterialTheme.colorScheme.error)
                }
                data.items.isEmpty() -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.ranking_share_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                else -> {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("ranking_share_pager"),
                        pageSpacing = 14.dp,
                        verticalAlignment = Alignment.CenterVertically
                    ) { page ->
                        val template = RankingShareTemplate.entries[page]
                        val count = minOf(template.limit, data.items.size)
                        val heading = stringResource(R.string.ranking_share_heading, count, scopeLabel)
                        RankingSharePreview(
                            request = RankingShareRenderRequest(
                                heading = heading,
                                items = data.items,
                                template = template,
                                showImages = showImages,
                                accentArgb = accentArgb,
                                backgroundPath = data.backgroundPath
                            ),
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(9f / 16f)
                                .testTag("ranking_share_preview_$page")
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RankingShareTemplate.entries.forEachIndexed { index, _ ->
                            val selected = pagerState.currentPage == index
                            val pageDescription = stringResource(
                                R.string.ranking_share_page_indicator,
                                index + 1,
                                RankingShareTemplate.entries.size,
                                if (selected) stringResource(R.string.ranking_share_selected) else ""
                            )
                            Box(
                                Modifier
                                    .size(if (selected) 10.dp else 8.dp)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                        CircleShape
                                    )
                                    .testTag("ranking_share_page_$index")
                                    .semantics {
                                        contentDescription = pageDescription
                                        this.selected = selected
                                    }
                            )
                        }
                    }
                }
            }
            val template = RankingShareTemplate.entries[pagerState.currentPage]
            val count = minOf(template.limit, data.items.size)
            val heading = stringResource(R.string.ranking_share_heading, count, scopeLabel)
            Button(
                enabled = data.items.isNotEmpty() && !data.loading && !data.failed && !exporting,
                onClick = {
                    onContinue(
                        RankingShareRenderRequest(
                            heading = heading,
                            items = data.items,
                            template = template,
                            showImages = showImages,
                            accentArgb = accentArgb,
                            backgroundPath = data.backgroundPath
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(bottom = 8.dp)
                    .testTag("ranking_share_continue")
            ) {
                if (exporting) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(stringResource(R.string.action_continue))
            }
        }
    }
}

@Composable
private fun RankingSharePreview(request: RankingShareRenderRequest, modifier: Modifier = Modifier) {
    var bitmap by remember(request) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(request) {
        bitmap = withContext(Dispatchers.Default) {
            RankingShareCardRenderer.render(
                request,
                RankingShareCardRenderer.PREVIEW_WIDTH,
                RankingShareCardRenderer.PREVIEW_HEIGHT
            )
        }
    }
    DisposableEffect(bitmap) {
        val rendered = bitmap
        onDispose { rendered?.recycle() }
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier
    ) {
        val current = bitmap
        if (current == null) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = stringResource(R.string.ranking_share_preview_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun shareScopeLabel(scope: ViewingScope): String = when (scope) {
    ViewingScope.All -> stringResource(R.string.ranking_share_scope_all)
    is ViewingScope.Pack -> stringResource(scope.mediaType.shareLabelResource())
}

private fun MediaType.shareLabelResource(): Int = when (this) {
    MediaType.ANIME -> R.string.ranking_share_scope_anime
    MediaType.MANGA -> R.string.ranking_share_scope_manga
    MediaType.MOVIE -> R.string.ranking_share_scope_movies
    MediaType.SERIES -> R.string.ranking_share_scope_series
    MediaType.BOOK -> R.string.ranking_share_scope_books
}

@Composable
private fun shareAccent(scope: ViewingScope): Int = when (scope) {
    ViewingScope.All -> MaterialTheme.colorScheme.primary.toArgb()
    is ViewingScope.Pack -> scope.mediaType.accentColor.toArgb()
}
