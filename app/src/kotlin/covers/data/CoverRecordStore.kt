package org.rankoffline.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Only the cover columns needed by the download coordinator. */
internal data class CoverRecord(
    val key: CatalogEntryKey,
    val remoteRef: String?,
    val localPath: String?,
    val state: String,
    val candidates: List<java.net.URI>,
    val metadataCandidates: List<CoverMetadataCandidate> = emptyList()
)

/** Room adapter. Differences between item and relation storage do not leak to the downloader. */
internal class CoverRecordStore(
    private val items: MediaDao,
    private val groups: MediaGroupDao,
    private val provider: DeclarativePackCoverProvider
) {
    suspend fun load(key: CatalogEntryKey): CoverRecord? = when (key.kind) {
        CatalogEntryKind.ITEM -> items.item(key.packId.value, key.id)?.let { entity ->
            CoverRecord(key, entity.remoteCoverRef, entity.localCoverPath,
                entity.coverResolutionState, provider.resolveCandidates(entity.toDomain()),
                provider.metadataCandidates(entity.toDomain()))
        }
        CatalogEntryKind.GROUP -> groups.group(key.packId.value, key.id)?.let { entity ->
            // Deliberately NO representative candidates here. Downloading those under
            // GROUP would create a second physical image in covers/groups/.
            val candidates = provider.resolveCandidates(entity.toDomain())
            CoverRecord(key, entity.remoteCoverRef, entity.localCoverPath,
                entity.coverResolutionState, candidates, provider.metadataCandidates(entity.toDomain()))
        }
    }


    fun hasRemoteSource(item: MediaItemEntity): Boolean =
        provider.resolveCandidates(item.toDomain()).isNotEmpty() ||
            provider.metadataCandidates(item.toDomain()).isNotEmpty()

    suspend fun fallbackItems(groupKey: CatalogEntryKey): List<MediaItemEntity> {
        require(groupKey.kind == CatalogEntryKind.GROUP)
        return groups.coverCandidateItems(groupKey.packId.value, groupKey.id)
    }

    fun observeFallbackItems(groupKey: CatalogEntryKey): Flow<List<MediaItemEntity>> {
        require(groupKey.kind == CatalogEntryKind.GROUP)
        // Room's invalidation subscription can wait for an unrelated import writer.
        // An indexed read gives an already READY group's cover immediately.
        return flow {
            emit(groups.coverCandidateItems(groupKey.packId.value, groupKey.id))
            emitAll(groups.observeCoverCandidateItems(groupKey.packId.value, groupKey.id))
        }
    }

    /** Reads the initial cover state immediately, then observes Room invalidations. */
    fun observeSnapshot(key: CatalogEntryKey): Flow<CoverSnapshotRow?> = flow {
        when (key.kind) {
            CatalogEntryKind.ITEM -> {
                emit(items.item(key.packId.value, key.id)?.let {
                    CoverSnapshotRow(it.localCoverPath, it.coverResolutionState, it.remoteCoverRef)
                })
                emitAll(items.observeCoverSnapshot(key.packId.value, key.id))
            }
            CatalogEntryKind.GROUP -> {
                emit(groups.group(key.packId.value, key.id)?.let {
                    CoverSnapshotRow(it.localCoverPath, it.coverResolutionState, it.remoteCoverRef)
                })
                emitAll(groups.observeCoverSnapshot(key.packId.value, key.id))
            }
        }
    }

    fun observePaths(packId: PackId): Pair<Flow<List<String>>, Flow<List<String>>> =
        items.observeLocalCoverPaths(packId.value) to groups.observeLocalCoverPaths(packId.value)

    /** Succeeds only if the record still references the same remote and local cover. */
    suspend fun updateIfCurrent(record: CoverRecord, path: String?, state: CoverResolutionState): Boolean =
        when (record.key.kind) {
            CatalogEntryKind.ITEM -> items.compareAndSetCover(
                record.key.packId.value, record.key.id, record.remoteRef, record.localPath,
                path, state.name
            )
            CatalogEntryKind.GROUP -> groups.compareAndSetCover(
                record.key.packId.value, record.key.id, record.remoteRef, record.localPath,
                path, state.name
            )
        } == 1

    suspend fun localRecords(packId: PackId): List<CoverRecord> = buildList {
        items.itemsWithLocalCovers(packId.value).forEach { entity ->
            add(CoverRecord(CatalogEntryKey(packId, entity.id, CatalogEntryKind.ITEM),
                entity.remoteCoverRef, entity.localCoverPath, entity.coverResolutionState, emptyList()))
        }
        groups.groupsWithLocalCovers(packId.value).forEach { entity ->
            add(CoverRecord(CatalogEntryKey(packId, entity.id, CatalogEntryKind.GROUP),
                entity.remoteCoverRef, entity.localCoverPath, entity.coverResolutionState, emptyList()))
        }
    }

    suspend fun ratedItems(packId: PackId, limit: Int): List<MediaItem> =
        items.ratedCoverItems(packId.value, limit).map { it.toDomain() }

    suspend fun popularItems(packId: PackId, limit: Int): List<MediaItem> =
        items.topPopularCoverItems(packId.value, limit).map { it.toDomain() }
}
