package org.rankoffline.app

import android.content.Context
import java.io.File

/** One canonical owner-aware namespace for pack-provided and user-downloaded covers. */
internal class CoverStorage(context: Context) {
    private val filesDir = context.applicationContext.filesDir

    fun downloadedItem(mediaType: MediaType, mediaId: String): File =
        File(downloadedRoot(mediaType), mediaCoverFileName(mediaId))

    fun downloadedGroup(mediaType: MediaType, groupId: String): File =
        File(File(downloadedRoot(mediaType), "groups"), mediaCoverFileName(groupId))

    fun isDownloaded(file: File, mediaType: MediaType): Boolean =
        file.isInside(downloadedRoot(mediaType)) || file.isInside(legacyScopedRoot(mediaType)) ||
            (mediaType == MediaType.ANIME && runCatching {
                file.canonicalFile.parentFile == legacyRoot().canonicalFile
            }.getOrDefault(false))

    fun isBundled(file: File, mediaType: MediaType): Boolean =
        file.isInside(File(File(File(filesDir, "packs"), mediaType.toPackId().value), "covers"))

    fun isRemovable(file: File, mediaType: MediaType): Boolean =
        isDownloaded(file, mediaType) || isBundled(file, mediaType)

    fun downloadedRoot(mediaType: MediaType): File =
        File(File(filesDir, "downloaded_covers"), mediaType.toPackId().value)

    fun legacyScopedRoot(mediaType: MediaType): File =
        File(legacyRoot(), mediaType.persistedValue)

    fun legacyRoot(): File = File(filesDir, "covers")

    /** Removes all app-owned cover cache files for one pack, including extracted pack covers. */
    fun clearCoverFiles(mediaType: MediaType): Boolean {
        val roots = buildList {
            add(downloadedRoot(mediaType))
            add(legacyScopedRoot(mediaType))
            add(File(File(File(filesDir, "packs"), mediaType.toPackId().value), "covers"))
        }
        val scopedCleared = roots.all { !it.exists() || it.deleteRecursively() }
        val legacyAnimeCleared = mediaType != MediaType.ANIME || legacyRoot().listFiles().orEmpty()
            .filter(File::isFile)
            .all { it.delete() || !it.exists() }
        return scopedCleared && legacyAnimeCleared
    }

    private fun File.isInside(directory: File): Boolean = runCatching {
        val root = directory.canonicalFile
        val candidate = canonicalFile
        candidate == root || candidate.path.startsWith(root.path + File.separator)
    }.getOrDefault(false)
}
