package org.rankoffline.app

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** One repository for all local images, regardless of where their bytes came from. */
class CoverRepository(
    context: Context,
    database: RankOfflineDatabase,
    internal val manifestProvider: (PackId) -> PackManifest?,
    internal val downloader: CoverDownloader = SecureCoverDownloader(),
    /** Must persist dirty bits before any filesystem/Room cover mutation. */
    internal val beforeCoverMutation: (PackId, Set<PackSummaryField>) -> Unit
) : CoverObservationSource {
    internal val catalogItems = database.mediaDao()
    internal val catalogGroups = database.mediaGroupDao()
    internal val storage = CoverStorage(context)
    internal val stagingRoot = File(context.applicationContext.cacheDir, "cover-staging")
    internal val records = CoverRecordStore(
        database.mediaDao(), database.mediaGroupDao(), DeclarativePackCoverProvider(manifestProvider)
    )
    internal val states = ConcurrentHashMap<CatalogEntryKey, MutableStateFlow<CoverDownloadState>>()

    /** One logical GROUP image: own validated file first, otherwise the existing
     * representative/member ITEM file. Nothing is copied and Room GROUP paths stay untouched. */
    override fun observeCover(key: CatalogEntryKey): Flow<CoverSnapshot> {
        val own = records.observeSnapshot(key)
        val observed = if (key.kind == CatalogEntryKind.ITEM) {
            own.map { row ->
                val itemPath = validItemCoverPath(key, row?.remoteRef, row?.localPath)
                CoverSnapshot(itemPath, if (itemPath == null) row.resolution()
                    else CoverResolutionState.AVAILABLE)
            }
        } else {
            combine(own, records.observeFallbackItems(key)) { row, members ->
                val ownPath = row?.localPath?.takeIf { validLocal(key, row.remoteRef, it) }
                    ?: row?.remoteRef?.takeIf { it.startsWith("covers/") }
                        ?.let { declaredCover(key.packId, it) }
                        ?.takeIf(CoverImageFiles::isValid)?.absolutePath
                val borrowed = if (ownPath == null) members.firstNotNullOfOrNull { item ->
                    val itemKey = CatalogEntryKey(key.packId, item.id, CatalogEntryKind.ITEM)
                    validItemCoverPath(itemKey, item.remoteCoverRef, item.localCoverPath)
                } else null
                when {
                    ownPath != null -> CoverSnapshot(ownPath, CoverResolutionState.AVAILABLE)
                    borrowed != null -> CoverSnapshot(borrowed, CoverResolutionState.AVAILABLE)
                    else -> CoverSnapshot(null, row.resolution())
                }
            }
        }
        return observed.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    internal fun CoverSnapshotRow?.resolution(): CoverResolutionState =
        CoverResolutionState.entries.firstOrNull { it.name == this?.resolutionState }
            ?: CoverResolutionState.UNKNOWN

    override fun observeLocalCoverPath(key: CatalogEntryKey): Flow<String?> =
        observeCover(key).map { it.localPath }.distinctUntilChanged()

    override fun observeDownloadState(key: CatalogEntryKey): StateFlow<CoverDownloadState> =
        states.getOrPut(key) { MutableStateFlow(CoverDownloadState.IDLE) }.asStateFlow()

    internal companion object {
        val inProgress = ConcurrentHashMap.newKeySet<CatalogEntryKey>()
        val generations = ConcurrentHashMap<PackId, AtomicLong>()
    }
}

/** Used only by explicit Top-X jobs, never by list rendering or app startup.
 * Each candidate source is counted once, so shared group representatives do not
 * consume multiple slots. A missing cover may still fail to download: X is the
 * number of distinct images ATTEMPTED, not a guarantee of X successful files. */
internal suspend fun <T> collectDistinctCoverKeys(
    limit: Int,
    pageSize: Int = 250,
    loadPage: suspend (offset: Int, count: Int) -> List<T>,
    sourceKeys: suspend (List<T>) -> List<CatalogEntryKey?>
): List<CatalogEntryKey> {
    require(limit in 1..5_000 && pageSize in 1..5_000)
    val distinct = LinkedHashSet<CatalogEntryKey>(limit)
    var offset = 0
    while (distinct.size < limit) {
        val count = minOf(pageSize, Int.MAX_VALUE - offset)
        if (count == 0) break
        val rows = loadPage(offset, count)
        if (rows.isEmpty()) break
        val keys = sourceKeys(rows)
        require(keys.size == rows.size) { "Cover source selection must preserve ordering" }
        for (key in keys) {
            if (key != null) distinct.add(key)
            if (distinct.size == limit) break
        }
        if (rows.size < count || offset > Int.MAX_VALUE - rows.size) break
        offset += rows.size
    }
    return distinct.toList()
}
