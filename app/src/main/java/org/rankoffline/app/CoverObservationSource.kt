package org.rankoffline.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** UI-facing cover state shared by legacy Anime and generic media repositories. */
interface CoverObservationSource {
    fun observeLocalCoverPath(mediaId: String): Flow<String?>
    fun observeDownloadState(mediaId: String): StateFlow<CoverDownloadState>
}
