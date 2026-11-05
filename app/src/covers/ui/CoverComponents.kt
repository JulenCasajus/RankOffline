package org.rankoffline.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Display-only derived state; all persisted cover facts come from Room via [CoverSnapshot]. */
internal data class CoverFrame(
    val snapshot: CoverSnapshot?,
    val bitmap: Bitmap?,
    val decodingFailed: Boolean,
    val downloading: Boolean
) {
    val loading: Boolean get() = snapshot == null ||
        (snapshot.localPath != null && bitmap == null && !decodingFailed)
    val notFound: Boolean get() = snapshot?.resolution == CoverResolutionState.NOT_FOUND &&
        snapshot.localPath == null
}

/**
 * Shared observation and off-main-thread decoding for thumbnail and full-screen viewer.
 * A null snapshot means the first database read has not replied yet. Once it replies,
 * UNKNOWN != NOT_FOUND. No extra persistent state or cover cache is created.
 */
@Composable
internal fun rememberCoverFrame(
    key: CatalogEntryKey,
    coverRepository: CoverRepository,
    bitmapCache: CoverBitmapCache,
    initialSnapshot: CoverSnapshot? = null
): CoverFrame {
    val snapshotFlow = remember(key, coverRepository) { coverRepository.observeCover(key) }
    val snapshot by snapshotFlow.collectAsState(initial = initialSnapshot)
    val downloadFlow = remember(key, coverRepository) { coverRepository.observeDownloadState(key) }
    val downloadState by downloadFlow.collectAsState()
    val path = snapshot?.localPath?.takeIf(String::isNotBlank)
    val cacheIdentity = remember(key) {
        CoverStorageIdentity(key.packId, key.id, key.kind).cacheIdentity()
    }

    var bitmap by remember(key, path, bitmapCache) {
        mutableStateOf(path?.let { bitmapCache.cachedBitmap(cacheIdentity, it) })
    }
    var decodingFailed by remember(key, path, bitmapCache) { mutableStateOf(false) }

    LaunchedEffect(key, path, bitmapCache) {
        if (path != null && bitmap == null) {
            val loaded = bitmapCache.loadBitmap(cacheIdentity, path)
            if (loaded == null) decodingFailed = true else bitmap = loaded
        }
    }

    return CoverFrame(
        snapshot = snapshot,
        bitmap = bitmap,
        decodingFailed = decodingFailed,
        downloading = downloadState == CoverDownloadState.DOWNLOADING
    )
}

/**
 * Lazy thumbnail for ANY item/group. The caller supplies the visible row's identity and title.
 * When images are disabled, this composable neither collects Room flows nor decodes bitmaps.
 * Passing an initial snapshot from the paged row avoids a loading flash on a warm cache.
 */
@Composable
internal fun MediaCover(
    key: CatalogEntryKey,
    title: String,
    coverRepository: CoverRepository,
    bitmapCache: CoverBitmapCache,
    onDownloadRequested: () -> Unit,
    onCoverClick: () -> Unit,
    modifier: Modifier = Modifier,
    showImages: Boolean = true,
    allowDownload: Boolean = true,
    initialSnapshot: CoverSnapshot? = null
) {
    if (!showImages) {
        Box(modifier.clip(RoundedCornerShape(10.dp)))
        return
    }
    val frame = rememberCoverFrame(key, coverRepository, bitmapCache, initialSnapshot)
    val shape = RoundedCornerShape(10.dp)
    val image = frame.bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape).clickable(role = Role.Button, onClick = onCoverClick)
        )
    } else {
        CoverPlaceholder(
            modifier = modifier,
            loading = frame.loading || frame.downloading,
            notFound = frame.notFound || frame.decodingFailed,
            enabled = allowDownload && !frame.loading && !frame.downloading,
            onClick = onDownloadRequested
        )
    }
}

@Composable
internal fun CoverPlaceholder(
    modifier: Modifier = Modifier,
    loading: Boolean,
    notFound: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.ImageIcon,
            contentDescription = stringResource(
                if (notFound) R.string.message_cover_not_found else R.string.cover_download
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Preferred entry-level API for new catalog/ranking cards. The callbacks receive the
 * ACTUAL cover key, which may be a GROUP; rating click handlers must still use entry.key.
 */
@Composable
internal fun MediaCover(
    entry: MediaListEntry,
    settings: AppSettingsState,
    coverRepository: CoverRepository,
    bitmapCache: CoverBitmapCache,
    onDownloadRequested: (CatalogEntryKey) -> Unit,
    onCoverClick: (CatalogEntryKey) -> Unit,
    modifier: Modifier = Modifier,
    showImages: Boolean = settings.mediaSettings[entry.packId]?.showImages == true,
    allowDownload: Boolean = true
) {
    val displayKey = entry.displayCoverKey(settings)
    // The paged row already carries this item's local path. Reuse a decoded
    // thumbnail immediately while Room emits its validated cover snapshot.
    val initialSnapshot = when (entry) {
        is MediaListEntry.Item -> entry.value.localCoverPath.takeIf { displayKey == entry.key }
        is MediaListEntry.Group -> entry.value.localCoverPath
    }?.takeIf { path ->
        val identity = CoverStorageIdentity(displayKey.packId, displayKey.id, displayKey.kind).cacheIdentity()
        bitmapCache.cachedBitmap(identity, path) != null
    }?.let { CoverSnapshot(it, CoverResolutionState.AVAILABLE) }
    MediaCover(
        key = displayKey, title = entry.title, coverRepository = coverRepository,
        bitmapCache = bitmapCache, modifier = modifier, showImages = showImages,
        allowDownload = allowDownload,
        initialSnapshot = initialSnapshot,
        onDownloadRequested = { onDownloadRequested(displayKey) },
        onCoverClick = { onCoverClick(displayKey) }
    )
}
