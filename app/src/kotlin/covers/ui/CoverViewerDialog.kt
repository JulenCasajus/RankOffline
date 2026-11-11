package org.rankoffline.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Full-size viewer shared by an item or a group; no Anime UI dependencies. */
@Composable
internal fun CoverViewerDialog(
    key: CatalogEntryKey,
    title: String,
    coverRepository: CoverRepository,
    bitmapCache: CoverBitmapCache,
    onDismiss: () -> Unit,
    onDeleteConfirmed: ((Boolean) -> Unit) -> Unit,
    subtitle: String? = null,
    groupMemberCount: Int? = null,
    onGroupDetails: (() -> Unit)? = null,
    onDownloadRequested: (() -> Unit)? = null,
    initialSnapshot: CoverSnapshot? = null
) {
    val frame = rememberCoverFrame(key, coverRepository, bitmapCache, initialSnapshot)
    var confirmDelete by remember(key) { mutableStateOf(false) }
    var deleting by remember(key) { mutableStateOf(false) }

    val localPath = frame.snapshot?.localPath
    val highRes by produceState<android.graphics.Bitmap?>(null, localPath, bitmapCache) {
        value = localPath?.let { bitmapCache.loadViewerBitmap(it) }
    }
    DisposableEffect(highRes) {
        val owned = highRes
        onDispose { owned?.recycle() }
    }
    val bitmap = highRes ?: frame.bitmap
    val imageRatio = bitmap?.let {
        (it.width.toFloat() / it.height.coerceAtLeast(1)).takeIf { ratio ->
            ratio.isFinite() && ratio > 0f
        }
    } ?: 2f / 3f

    Dialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            val viewerWidth = minOf(maxWidth, 560.dp)
            val maxImageHeight = (maxHeight - 184.dp).coerceAtLeast(120.dp)
            val imageHeight = minOf(viewerWidth / imageRatio, maxImageHeight)
            Surface(
                modifier = Modifier.width(viewerWidth).wrapContentHeight(),
                color = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismiss, enabled = !deleting) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                    }
                    if (groupMemberCount != null || !subtitle.isNullOrBlank()) {
                        val groupLabel = groupMemberCount?.let {
                            stringResource(R.string.group_entries_subtitle_count, it)
                        }
                        val detail = listOfNotNull(groupLabel, subtitle?.takeIf(String::isNotBlank))
                            .joinToString(if (groupLabel == null) "" else " | ")
                        Text(
                            text = detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().then(if (groupLabel != null && onGroupDetails != null)
                                Modifier.clickable(role = Role.Button, onClick = onGroupDetails)
                                else Modifier)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    Box(
                        modifier = Modifier.fillMaxWidth().height(imageHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            bitmap != null -> Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = title,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                            frame.loading || frame.downloading -> CircularProgressIndicator()
                            else -> Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    if (frame.notFound) Icons.Default.ImageIcon else Icons.Default.Download,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (frame.notFound) stringResource(R.string.message_cover_not_found)
                                    else stringResource(R.string.cover_download),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    when {
                        frame.snapshot?.localPath != null -> {
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
                        frame.canRequestDownload && onDownloadRequested != null -> {
                            OutlinedButton(
                                onClick = onDownloadRequested,
                                enabled = !deleting,
                                modifier = Modifier.fillMaxWidth().padding(12.dp)
                            ) {
                                Text(stringResource(R.string.cover_download))
                            }
                        }
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
                TextButton(enabled = !deleting, onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        onDeleteConfirmed { removed ->
                            deleting = false
                            confirmDelete = false
                            if (removed) {
                                frame.snapshot?.localPath?.let { path ->
                                    val identity = CoverStorageIdentity(key.packId, key.id, key.kind)
                                        .cacheIdentity()
                                    bitmapCache.invalidate(identity, path)
                                }
                                onDismiss()
                            }
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        )
    }
}

/** Viewer for a visible item or group. Group fallback is resolved by CoverRepository. */
@Composable
internal fun CoverViewerDialog(
    entry: MediaListEntry,
    settings: AppSettingsState,
    coverRepository: CoverRepository,
    bitmapCache: CoverBitmapCache,
    onDismiss: () -> Unit,
    onDeleteConfirmed: (CatalogEntryKey, (Boolean) -> Unit) -> Unit,
    subtitle: String? = null,
    subtitleFields: List<PackFieldDefinition> = emptyList(),
    onGroupDetails: (() -> Unit)? = null,
    onDownloadRequested: ((CatalogEntryKey) -> Unit)? = null
) {
    val imageKey = entry.key
    val memberCount = (entry as? MediaListEntry.Group)?.memberCount
    val languageTag = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
        ?.toLanguageTag() ?: "en"
    val subtitleLabels = mutableListOf<String>()
    for (part in entry.subtitleParts(subtitleFields, languageTag)) {
        subtitleLabels += part.displayText()
    }
    val displaySubtitle = subtitle ?: subtitleLabels.joinToString(" · ")
    CoverViewerDialog(
        key = imageKey, title = entry.title, coverRepository = coverRepository,
        bitmapCache = bitmapCache, onDismiss = onDismiss,
        onDeleteConfirmed = { finished -> onDeleteConfirmed(imageKey, finished) },
        subtitle = displaySubtitle, groupMemberCount = memberCount,
        onGroupDetails = onGroupDetails,
        onDownloadRequested = onDownloadRequested?.let { request -> { request(imageKey) } }
    )
}
