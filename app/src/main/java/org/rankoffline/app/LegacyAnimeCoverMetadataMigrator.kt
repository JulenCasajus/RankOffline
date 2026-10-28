package org.rankoffline.app

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-shot bridge for cover metadata written by v0.1.3. It intentionally depends only on the
 * legacy DAO surface retained for database migration; all normal cover operations use media_*.
 */
internal class LegacyAnimeCoverMetadataMigrator(
    context: Context,
    database: AnimeDatabase
) {
    private val appContext = context.applicationContext
    private val legacyDao = database.animeDao()
    private val storage = CoverStorage(appContext)

    suspend fun migrate() = withContext(Dispatchers.IO) {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val consumed = mutableListOf<String>()
        preferences.all.forEach { (key, rawValue) ->
            val url = rawValue as? String ?: return@forEach
            val ids = when {
                key.startsWith(ANILIST_PREFIX) -> key.removePrefix(ANILIST_PREFIX).toLongOrNull()
                    ?.let { legacyDao.animeIdsByAnilistId(it) }
                key.startsWith(MAL_PREFIX) -> key.removePrefix(MAL_PREFIX).toLongOrNull()
                    ?.let { legacyDao.animeIdsByMalId(it) }
                else -> null
            } ?: return@forEach
            if (ids.isEmpty()) return@forEach

            try {
                val legacyFile = File(storage.legacyRoot(), "${url.hashCode()}.jpg")
                val validFile = legacyFile.isFile && validImage(legacyFile)
                ids.forEach { animeId ->
                    legacyDao.updateRemoteCover(animeId, url, CoverResolutionState.AVAILABLE.name)
                    if (validFile) {
                        val destination = storage.downloadedItem(MediaType.ANIME, animeId)
                        destination.parentFile?.mkdirs()
                        val temporary = File.createTempFile(
                            "${destination.nameWithoutExtension}-",
                            ".part",
                            destination.parentFile
                        )
                        try {
                            legacyFile.inputStream().use { input ->
                                temporary.outputStream().use(input::copyTo)
                            }
                            moveAtomically(temporary, destination)
                            legacyDao.updateLocalCover(
                                animeId,
                                destination.absolutePath,
                                CoverResolutionState.AVAILABLE.name
                            )
                        } finally {
                            temporary.delete()
                        }
                    }
                }
                consumed += key
            } catch (exception: Exception) {
                Log.w(TAG, "Unable to migrate legacy Anime cover metadata for $key", exception)
            }
        }
        if (consumed.isNotEmpty()) {
            preferences.edit().also { editor -> consumed.forEach(editor::remove) }.apply()
        }
    }

    private fun validImage(file: File): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return file.length() > 0L && options.outWidth > 0 && options.outHeight > 0
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "cover_metadata"
        const val ANILIST_PREFIX = "anilist_"
        const val MAL_PREFIX = "mal_"
        const val TAG = "RankOfflineCover"
    }
}
