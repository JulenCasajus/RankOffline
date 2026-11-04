package org.rankoffline.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Persisted Room state; null path + UNKNOWN is NOT "cover missing". */
data class CoverSnapshot(
    val localPath: String?,
    val resolution: CoverResolutionState
)

/** UI-facing cover state keyed by pack, entry ID and entry kind. */
interface CoverObservationSource {
    fun observeCover(key: CatalogEntryKey): Flow<CoverSnapshot>
    fun observeLocalCoverPath(key: CatalogEntryKey): Flow<String?>
    fun observeDownloadState(key: CatalogEntryKey): StateFlow<CoverDownloadState>
}
