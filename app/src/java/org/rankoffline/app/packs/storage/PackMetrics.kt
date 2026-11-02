package org.rankoffline.app

import kotlinx.coroutines.flow.first

/** Indexed Room counts on demand. Only available covers require validating local image files. */
class RoomPackMetrics(
    private val database: RankOfflineDatabase,
    private val covers: () -> CoverRepository
) {
    suspend fun itemCount(packId: PackId): Long =
        database.mediaCatalogDao().itemCount(packId.value)

    suspend fun groupCount(packId: PackId): Long =
        database.mediaGroupDao().groupCountForPack(packId.value)

    suspend fun availableCoverCount(packId: PackId): Long =
        covers().observeLocalCoverCount(packId).first().toLong()

    /** Count already-classified NOT_FOUND entries; does NOT retry remote URLs. */
    suspend fun missingCoverCount(packId: PackId): Long =
        database.mediaDao().notFoundCoverCount(packId.value) +
            database.mediaGroupDao().notFoundGroupCoverCount(packId.value)
}
