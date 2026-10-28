package org.rankoffline.app

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private sealed interface ObservedCoverPath {
    data object Resolving : ObservedCoverPath
    data class Resolved(val path: String?) : ObservedCoverPath
}

@Composable
internal fun AnimeCover(
    anime: Anime,
    coverRepository: CoverBitmapCache,
    genericCoverRepository: GenericMediaCoverRepository? = null,
    genericGroupCoverRepository: GenericMediaGroupCoverRepository? = null,
    onDownloadRequested: () -> Unit,
    onCoverClick: () -> Unit,
    modifier: Modifier = Modifier,
    allowDownload: Boolean = true
) {
    val storageIdentity = remember(anime.id, anime.groupId, anime.mediaType) {
        anime.coverStorageIdentity()
    }
    val observationSource: CoverObservationSource? = remember(
        storageIdentity, coverRepository, genericCoverRepository, genericGroupCoverRepository
    ) {
        when (storageIdentity.kind) {
            CoverStorageKind.GENERIC_ITEM -> genericCoverRepository
            CoverStorageKind.GENERIC_GROUP -> genericGroupCoverRepository
        }
    }
    val pathFlow = remember(storageIdentity, anime.localCoverPath, observationSource) {
        observationSource?.observeLocalCoverPath(storageIdentity.id) ?: flowOf(anime.localCoverPath)
    }
    val immediatePath = anime.localCoverPath
    val localPath by pathFlow.collectAsState(initial = immediatePath)
    val downloadStateFlow = remember(storageIdentity, observationSource) {
        observationSource?.observeDownloadState(storageIdentity.id) ?: flowOf(CoverDownloadState.IDLE)
    }
    val downloadState by downloadStateFlow.collectAsState(initial = CoverDownloadState.IDLE)
    var invalidLocalPath by remember(anime.id, localPath) { mutableStateOf(false) }
    val effectiveLocalPath = localPath.takeUnless { invalidLocalPath }
    var bitmap by remember(storageIdentity, effectiveLocalPath) {
        mutableStateOf(effectiveLocalPath?.let { coverRepository.cachedBitmap(storageIdentity.id, it) })
    }

    LaunchedEffect(storageIdentity, effectiveLocalPath) {
        val currentLocalPath = effectiveLocalPath
        if (currentLocalPath != null && bitmap == null) {
            val loaded = coverRepository.loadBitmap(storageIdentity.id, currentLocalPath)
            if (loaded == null) {
                invalidLocalPath = true
            } else bitmap = loaded
        } else if (currentLocalPath == null) bitmap = null
    }

    val image = bitmap
    when {
        image != null -> Image(
            bitmap = image.asImageBitmap(),
            contentDescription = anime.title,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(10.dp)).clickable {
                dispatchCoverTap(effectiveLocalPath, onDownloadRequested, onCoverClick)
            }
        )
        effectiveLocalPath == null -> DefaultCover(
            modifier = modifier,
            downloading = downloadState == CoverDownloadState.DOWNLOADING,
            enabled = allowDownload,
            onClick = { dispatchCoverTap(null, onDownloadRequested, onCoverClick) }
        )
        else -> Box(
            modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clip(RoundedCornerShape(10.dp))
        )
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
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled && !downloading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Image, stringResource(R.string.cover_download), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        if (downloading) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoverViewerDialog(
    coverAnime: Anime,
    displayAnime: Anime,
    coverRepository: CoverBitmapCache,
    genericCoverRepository: GenericMediaCoverRepository? = null,
    genericGroupCoverRepository: GenericMediaGroupCoverRepository? = null,
    onDismiss: () -> Unit,
    onGroupDetails: (() -> Unit)?,
    onDeleteConfirmed: ((Boolean) -> Unit) -> Unit
) {
    val storageIdentity = remember(coverAnime.id, coverAnime.groupId, coverAnime.mediaType) {
        coverAnime.coverStorageIdentity()
    }
    val observationSource: CoverObservationSource? = remember(
        storageIdentity, coverRepository, genericCoverRepository, genericGroupCoverRepository
    ) {
        when (storageIdentity.kind) {
            CoverStorageKind.GENERIC_ITEM -> genericCoverRepository
            CoverStorageKind.GENERIC_GROUP -> genericGroupCoverRepository
        }
    }
    val pathFlow = remember(storageIdentity, coverAnime.localCoverPath, observationSource) {
        observationSource?.observeLocalCoverPath(storageIdentity.id) ?: flowOf(coverAnime.localCoverPath)
    }
    val initialPath = coverAnime.localCoverPath
    val observedPathFlow = remember(pathFlow, storageIdentity) {
        pathFlow.map<String?, ObservedCoverPath>(ObservedCoverPath::Resolved)
    }
    val observedPath by observedPathFlow.collectAsState(
        initial = initialPath?.let(ObservedCoverPath::Resolved) ?: ObservedCoverPath.Resolving
    )
    val localPath = (observedPath as? ObservedCoverPath.Resolved)?.path
    var bitmap by remember(storageIdentity, localPath) {
        mutableStateOf(localPath?.let { coverRepository.cachedBitmap(storageIdentity.id, it) })
    }
    var decodeFailed by remember(storageIdentity, localPath) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val imageAspectRatio = bitmap?.let { image ->
        image.width.toFloat().div(image.height.toFloat()).takeIf { it.isFinite() && it > 0f }
    } ?: (2f / 3f)

    LaunchedEffect(storageIdentity, localPath) {
        val currentPath = localPath
        if (currentPath != null && bitmap == null) {
            val loaded = coverRepository.loadBitmap(storageIdentity.id, currentPath)
            if (loaded == null) {
                decodeFailed = true
                Log.w(
                    "RankOfflineCover",
                    "Unable to decode ${storageIdentity.kind} cover ${storageIdentity.id}"
                )
            } else {
                bitmap = loaded
                decodeFailed = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            val viewerWidth = minOf(maxWidth, 560.dp)
            val maxImageHeight = (maxHeight - 184.dp).coerceAtLeast(120.dp)
            val imageHeight = minOf(viewerWidth / imageAspectRatio, maxImageHeight)
            Surface(
                Modifier.width(viewerWidth).wrapContentHeight(),
                color = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimeTopAppBar(displayAnime.localizedDisplayTitle(), onDismiss)
                    if (displayAnime.usesGroupEntriesCoverSubtitle() && onGroupDetails != null) {
                        Text(
                            stringResource(R.string.group_entries_count, displayAnime.memberCount),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onGroupDetails)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    } else {
                        val subtitle = coverAnime.metadataSubtitle()
                        if (subtitle.isNotEmpty()) Text(
                            subtitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(imageHeight)
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            bitmap != null -> Image(
                                bitmap!!.asImageBitmap(),
                                displayAnime.title,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                            observedPath is ObservedCoverPath.Resolving ||
                                (localPath != null && !decodeFailed) ->
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.message_cover_not_found),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (localPath != null) {
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            enabled = !deleting,
                            modifier = Modifier.fillMaxWidth().padding(12.dp)
                        ) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.cover_delete))
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { if (!deleting) confirmDelete = false },
        title = { Text(stringResource(R.string.cover_delete_title)) },
        text = { Text(stringResource(R.string.cover_delete_explanation)) },
        dismissButton = {
            TextButton(enabled = !deleting, onClick = { confirmDelete = false }) {
                Text(stringResource(R.string.action_cancel))
            }
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
            ) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
        }
    )
}
