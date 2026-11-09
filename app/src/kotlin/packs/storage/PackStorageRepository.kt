package org.rankoffline.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Estimated logical size of a pack's personal data, not the physical SQLite/DataStore size.
 * Catalog files and cached covers are accounted for separately.
 *
 * Rating rows remain attributable to their PackId even after the catalog is uninstalled.
 */
data class PackPersonalStorage(
    val logicalDatabaseBytes: Long = 0L,
    val logicalPreferencesBytes: Long = 0L
) {
    val logicalTotalBytes: Long get() = logicalDatabaseBytes + logicalPreferencesBytes
}

/**
 * Observes the personal-data footprint of any pack, including uninstalled packs.
 *
 * [preferenceBytesByPack] is produced by the settings layer from its serialized
 * per-pack settings. Keeping that responsibility with the settings layer avoids
 * coupling storage accounting to a particular settings schema or serialization.
 * The values must be nonnegative logical byte estimates.
 */
class PackStorageRepository(
    database: RankOfflineDatabase,
    private val preferenceBytesByPack: Flow<Map<PackId, Long>>
) {
    private val dao = database.mediaCatalogDao()

    fun observePersonalStorage(packId: PackId): Flow<PackPersonalStorage> {
        val id = packId.value
        val ratingBytes = combine(
            dao.observeItemRatingLogicalBytes(id),
            dao.observeItemScoreLogicalBytes(id),
            dao.observeGroupRatingLogicalBytes(id),
            dao.observeGroupScoreLogicalBytes(id)
        ) { itemRatings, itemScores, groupRatings, groupScores ->
            itemRatings + itemScores + groupRatings + groupScores
        }

        return combine(
            ratingBytes,
            preferenceBytesByPack.map { preferences -> preferences[packId] ?: 0L }
        ) { databaseBytes, preferenceBytes ->
            require(databaseBytes >= 0L && preferenceBytes >= 0L) {
                "Personal storage estimates must be nonnegative"
            }
            PackPersonalStorage(
                logicalDatabaseBytes = databaseBytes,
                logicalPreferencesBytes = preferenceBytes
            )
        }.distinctUntilChanged()
    }
}
