package org.rankoffline.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class PackPersonalStorage(
    /** Logical payload owned by this pack; SQLite pages are shared and cannot be split exactly. */
    val logicalDatabaseBytes: Long = 0L,
    val logicalPreferencesBytes: Long = 0L
) {
    val logicalTotalBytes: Long get() = logicalDatabaseBytes + logicalPreferencesBytes
}

class PackStorageRepository(
    database: AnimeDatabase,
    settings: Flow<AppSettingsState>
) {
    private val dao = database.mediaCatalogDao()
    private val json = Json { encodeDefaults = true }
    private val settingsFlow = settings

    fun observePersonalStorage(mediaType: MediaType): Flow<PackPersonalStorage> {
        val packId = mediaType.toPackId().value
        val databaseBytes = combine(
            dao.observeItemRatingLogicalBytes(packId),
            dao.observeItemScoreLogicalBytes(packId),
            dao.observeGroupRatingLogicalBytes(packId),
            dao.observeGroupScoreLogicalBytes(packId)
        ) { itemRatings, itemScores, groupRatings, groupScores ->
            itemRatings + itemScores + groupRatings + groupScores
        }
        return combine(
            databaseBytes,
            settingsFlow.map { state ->
                state.mediaSettings[mediaType]?.let { value ->
                    json.encodeToString(value).encodeToByteArray().size.toLong()
                } ?: 0L
            }
        ) { roomBytes, preferenceBytes ->
            PackPersonalStorage(roomBytes, preferenceBytes)
        }.distinctUntilChanged()
    }
}
