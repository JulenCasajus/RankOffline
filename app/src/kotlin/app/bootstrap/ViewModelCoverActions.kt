package org.rankoffline.app


fun RankOfflineViewModel.downloadCover(key: CatalogEntryKey) = covers.download(key)


fun RankOfflineViewModel.downloadTopCovers(packId: PackId, limit: Int) = covers.downloadTop(packId, limit)


fun RankOfflineViewModel.cancelCoverDownloads(packId: PackId) = covers.cancelBatch(packId)

fun RankOfflineViewModel.setCoverProgressVisible(packId: PackId, visible: Boolean) =
    covers.setProgressVisible(packId, visible)


fun RankOfflineViewModel.deleteDownloadedCover(key: CatalogEntryKey, onComplete: (Boolean) -> Unit = {}) = covers.delete(key, onComplete)


fun RankOfflineViewModel.clearDownloadedCovers(packId: PackId) = covers.clear(packId)


fun RankOfflineViewModel.observeDownloadedCoverCount(packId: PackId) =
    services.mediaCoverRepository.observeLocalCoverCount(packId)

// Personal JSON backup and optional ranking-image sharing.
