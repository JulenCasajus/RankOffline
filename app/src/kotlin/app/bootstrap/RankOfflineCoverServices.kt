package org.rankoffline.app

/** Manual and Top-N requests share the image transfer gate inside the downloader. */
internal suspend fun RankOfflineServices.downloadCover(key: CatalogEntryKey): CoverDownloadResult =
    mediaCoverRepository.downloadCover(key)
