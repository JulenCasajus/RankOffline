package org.rankoffline.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class CoverRepository(context: Context, private val animeRepository: AnimeRepository) {
    private val appContext = context.applicationContext
    private val resolvingAnime = ConcurrentHashMap.newKeySet<String>()
    private val downloadStates = ConcurrentHashMap<String, MutableStateFlow<CoverDownloadState>>()
    private val localPathSnapshots = ConcurrentHashMap<String, LocalPathSnapshot>()
    private val memoryCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceAtLeast(1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    suspend fun initialize() = withContext(Dispatchers.IO) { migrateLegacyCoverMetadata() }

    fun observeLocalCoverPath(animeId: String): Flow<String?> =
        animeRepository.observeLocalCoverPath(animeId).distinctUntilChanged()

    fun immediateLocalCoverPath(animeId: String, databaseValue: String?): String? {
        val existing = localPathSnapshots.putIfAbsent(animeId, LocalPathSnapshot(databaseValue))
        return existing?.path ?: databaseValue
    }

    fun observeDownloadState(animeId: String): StateFlow<CoverDownloadState> =
        downloadStates.getOrPut(animeId) { MutableStateFlow(CoverDownloadState.IDLE) }.asStateFlow()

    fun cachedBitmap(animeId: String, path: String): Bitmap? =
        synchronized(memoryCache) { memoryCache.get(cacheKey(animeId, path)) }

    suspend fun loadBitmap(animeId: String, path: String): Bitmap? = withContext(Dispatchers.IO) {
        cachedBitmap(animeId, path)?.let { return@withContext it }
        val bitmap = BitmapFactory.decodeFile(path) ?: return@withContext null
        synchronized(memoryCache) { memoryCache.put(cacheKey(animeId, path), bitmap) }
        bitmap
    }

    suspend fun invalidateLocalCover(anime: Anime, path: String) = withContext(Dispatchers.IO) {
        val state = if (anime.remoteCoverUrl.isNullOrBlank()) CoverResolutionState.UNKNOWN
        else CoverResolutionState.AVAILABLE
        animeRepository.updateLocalCover(anime.id, null, state)
        localPathSnapshots[anime.id] = LocalPathSnapshot(null)
        synchronized(memoryCache) { memoryCache.remove(cacheKey(anime.id, path)) }
        val file = File(path)
        if (!file.delete() && file.exists()) Log.w(TAG, "Unable to remove invalid cover for ${anime.id}")
    }

    /**
     * Explicit operation for a detail action or future opt-in preload.
     * Lazy-list composition never calls this method.
     */
    suspend fun downloadCover(anime: Anime, knownRemoteUrl: String? = null): CoverDownloadResult = withContext(Dispatchers.IO) {
        val currentLocalPath = immediateLocalCoverPath(anime.id, anime.localCoverPath)
        if (!currentLocalPath.isNullOrBlank()) return@withContext CoverDownloadResult.AlreadyAvailable
        if (anime.coverResolutionState == CoverResolutionState.NOT_FOUND && knownRemoteUrl.isNullOrBlank()) {
            return@withContext CoverDownloadResult.NotFound
        }
        if (!resolvingAnime.add(anime.id)) return@withContext CoverDownloadResult.AlreadyInProgress
        val state = downloadStates.getOrPut(anime.id) { MutableStateFlow(CoverDownloadState.IDLE) }
        state.value = CoverDownloadState.DOWNLOADING
        try {
            if (installBundledCover(anime)) return@withContext CoverDownloadResult.Downloaded
            val directUrl = knownRemoteUrl?.takeIf(String::isNotBlank)
                ?: anime.remoteCoverUrl?.takeIf(String::isNotBlank)
            directUrl?.let { url ->
                if (url != anime.remoteCoverUrl) {
                    animeRepository.updateRemoteCover(anime.id, url, CoverResolutionState.AVAILABLE)
                }
                return@withContext when (val result = installDownloadedCover(anime.id, url)) {
                    DownloadResult.INSTALLED -> CoverDownloadResult.Downloaded
                    DownloadResult.NOT_FOUND -> {
                        persistResolutionResult(anime, CoverDownloadResult.NotFound)
                        CoverDownloadResult.NotFound
                    }
                    DownloadResult.NETWORK_ERROR -> CoverDownloadResult.NetworkError
                    DownloadResult.TEMPORARY_ERROR -> CoverDownloadResult.TemporaryError
                }
            }

            var sawTemporaryError = false
            var sawNetworkError = false
            var found: LookupResult.Found? = null
            anime.anilistId?.let { id ->
                when (val result = fetchAniListCoverUrl(id)) {
                    is LookupResult.Found -> found = result
                    LookupResult.NetworkError -> sawNetworkError = true
                    LookupResult.TemporaryError -> sawTemporaryError = true
                    LookupResult.NotFound -> Unit
                }
            }
            if (found == null) anime.malId?.let { id ->
                when (val result = fetchJikanCoverUrl(id)) {
                    is LookupResult.Found -> found = result
                    LookupResult.NetworkError -> sawNetworkError = true
                    LookupResult.TemporaryError -> sawTemporaryError = true
                    LookupResult.NotFound -> Unit
                }
            }
            if (found != null) {
                val url = requireNotNull(found).url
                animeRepository.updateRemoteCover(anime.id, url, CoverResolutionState.AVAILABLE)
                when (installDownloadedCover(anime.id, url)) {
                    DownloadResult.INSTALLED -> CoverDownloadResult.Downloaded
                    DownloadResult.NOT_FOUND -> {
                        persistResolutionResult(anime, CoverDownloadResult.NotFound)
                        CoverDownloadResult.NotFound
                    }
                    DownloadResult.NETWORK_ERROR -> CoverDownloadResult.NetworkError
                    DownloadResult.TEMPORARY_ERROR -> CoverDownloadResult.TemporaryError
                }
            } else if (!sawTemporaryError && !sawNetworkError) {
                persistResolutionResult(anime, CoverDownloadResult.NotFound)
                CoverDownloadResult.NotFound
            } else if (sawNetworkError) {
                CoverDownloadResult.NetworkError
            } else {
                CoverDownloadResult.TemporaryError
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            Log.w(TAG, "Cover operation failed for ${anime.id}", exception)
            if (exception.isNetworkFailure()) CoverDownloadResult.NetworkError
            else CoverDownloadResult.TemporaryError
        } catch (exception: Exception) {
            Log.w(TAG, "Cover operation failed for ${anime.id}", exception)
            CoverDownloadResult.TemporaryError
        } finally {
            resolvingAnime.remove(anime.id)
            state.value = CoverDownloadState.IDLE
        }
    }

    suspend fun downloadPreselectedCover(anime: Anime, selectedUrl: String?): CoverDownloadResult {
        if (selectedUrl.isNullOrBlank() && anime.remoteCoverUrl.isNullOrBlank()) {
            persistResolutionResult(anime, CoverDownloadResult.NotFound)
            return CoverDownloadResult.NotFound
        }
        return downloadCover(anime, selectedUrl)
    }

    private suspend fun persistResolutionResult(anime: Anime, result: CoverDownloadResult) {
        val next = CoverResolutionPolicy.afterDownload(anime.coverResolutionState, result)
        if (next != anime.coverResolutionState) {
            animeRepository.updateCoverResolutionState(anime.id, next)
        }
    }

    suspend fun deleteDownloadedCover(anime: Anime): Boolean = withContext(Dispatchers.IO) {
        val path = immediateLocalCoverPath(anime.id, anime.localCoverPath)
            ?: return@withContext true
        val directory = coverDirectory().canonicalFile
        val file = runCatching { File(path).canonicalFile }.getOrNull()
            ?: return@withContext false
        if (!file.path.startsWith(directory.path + File.separator)) return@withContext false
        if (file.exists() && !file.delete()) return@withContext false

        animeRepository.clearLocalCoverPath(anime.id)
        localPathSnapshots[anime.id] = LocalPathSnapshot(null)
        synchronized(memoryCache) { memoryCache.remove(cacheKey(anime.id, path)) }
        true
    }

    suspend fun clearDownloadedCovers(): Int = withContext(Dispatchers.IO) {
        val paths = animeRepository.allLocalCoverPaths()
        val directory = coverDirectory().canonicalFile
        paths.forEach { path ->
            val file = runCatching { File(path).canonicalFile }.getOrNull()
            if (file != null && file.path.startsWith(directory.path + File.separator)) {
                if (!file.delete() && file.exists()) Log.w(TAG, "Unable to remove cover ${file.name}")
            }
        }
        directory.listFiles()?.filter { it.extension == "part" }?.forEach(File::delete)
        animeRepository.clearAllLocalCoverPaths()
        localPathSnapshots.keys.forEach { animeId ->
            localPathSnapshots[animeId] = LocalPathSnapshot(null)
        }
        synchronized(memoryCache) { memoryCache.evictAll() }
        paths.size
    }

    private suspend fun migrateLegacyCoverMetadata() {
        val preferences = appContext.getSharedPreferences("cover_metadata", Context.MODE_PRIVATE)
        val consumedKeys = mutableListOf<String>()
        preferences.all.forEach { (key, value) ->
            val url = value as? String ?: return@forEach
            val animeIds = when {
                key.startsWith("anilist_") -> key.removePrefix("anilist_").toLongOrNull()
                    ?.let { animeRepository.animeIdsByAnilistId(it) }
                key.startsWith("mal_") -> key.removePrefix("mal_").toLongOrNull()
                    ?.let { animeRepository.animeIdsByMalId(it) }
                else -> null
            } ?: return@forEach
            if (animeIds.isEmpty()) return@forEach

            try {
                val legacyFile = legacyCacheFile(url)
                val validLegacyFile = legacyFile.isFile && isValidImage(legacyFile)
                animeIds.forEach { animeId ->
                    animeRepository.updateRemoteCover(animeId, url, CoverResolutionState.AVAILABLE)
                    if (validLegacyFile) installCover(animeId, legacyFile)
                }
                consumedKeys += key
            } catch (exception: Exception) {
                Log.w(TAG, "Unable to migrate legacy cover metadata for $key", exception)
            }
        }
        if (consumedKeys.isNotEmpty()) {
            preferences.edit().also { editor -> consumedKeys.forEach(editor::remove) }.apply()
        }
    }

    private fun fetchAniListCoverUrl(anilistId: Long): LookupResult {
        val query = """
            query (${'$'}id: Int!) {
              Media(id: ${'$'}id, type: ANIME) {
                coverImage { extraLarge large medium }
              }
            }
        """.trimIndent()
        return try {
            val response = postJson(
                "https://graphql.anilist.co",
                JSONObject().put("query", query).put("variables", JSONObject().put("id", anilistId.toInt()))
            )
            if (response.optJSONArray("errors") != null) return LookupResult.TemporaryError
            val cover = response.optJSONObject("data")?.optJSONObject("Media")?.optJSONObject("coverImage")
            val url = cover?.optString("extraLarge")?.takeIf(String::isNotBlank)
                ?: cover?.optString("large")?.takeIf(String::isNotBlank)
                ?: cover?.optString("medium")?.takeIf(String::isNotBlank)
            if (url == null) LookupResult.NotFound else LookupResult.Found(url)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            Log.w(TAG, "Temporary AniList cover lookup failure for ID $anilistId", exception)
            if (exception.isNetworkFailure()) LookupResult.NetworkError else LookupResult.TemporaryError
        } catch (exception: Exception) {
            Log.w(TAG, "Temporary AniList cover lookup failure for ID $anilistId", exception)
            LookupResult.TemporaryError
        }
    }

    private fun fetchJikanCoverUrl(malId: Long): LookupResult {
        return try {
            val connection = openHttps("https://api.jikan.moe/v4/anime/$malId")
            try {
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND) return LookupResult.NotFound
                if (code !in 200..299) return LookupResult.TemporaryError
                val data = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                    .optJSONObject("data")?.optJSONObject("images")?.optJSONObject("jpg")
                val url = data?.optString("large_image_url")?.takeIf(String::isNotBlank)
                    ?: data?.optString("image_url")?.takeIf(String::isNotBlank)
                if (url == null) LookupResult.NotFound else LookupResult.Found(url)
            } finally {
                connection.disconnect()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            Log.w(TAG, "Temporary Jikan cover lookup failure for ID $malId", exception)
            if (exception.isNetworkFailure()) LookupResult.NetworkError else LookupResult.TemporaryError
        } catch (exception: Exception) {
            Log.w(TAG, "Temporary Jikan cover lookup failure for ID $malId", exception)
            LookupResult.TemporaryError
        }
    }

    private suspend fun installDownloadedCover(animeId: String, remoteUrl: String): DownloadResult {
        if (!remoteUrl.startsWith("https://")) return DownloadResult.NOT_FOUND
        val destination = coverFile(animeId)
        destination.parentFile?.mkdirs()
        val temporary = File.createTempFile("${destination.nameWithoutExtension}-", ".part", destination.parentFile)
        return try {
            var lastFailure = DownloadResult.TEMPORARY_ERROR
            repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
                try {
                    val connection = openHttps(remoteUrl)
                    try {
                        val code = connection.responseCode
                        if (code == HttpURLConnection.HTTP_NOT_FOUND) return DownloadResult.NOT_FOUND
                        if (code !in 200..299) {
                            lastFailure = DownloadResult.TEMPORARY_ERROR
                        } else {
                            connection.inputStream.use { input -> temporary.outputStream().use(input::copyTo) }
                            val bitmap = BitmapFactory.decodeFile(temporary.absolutePath)
                                ?: return DownloadResult.TEMPORARY_ERROR
                            moveAtomically(temporary, destination)
                            animeRepository.updateLocalCover(animeId, destination.absolutePath, CoverResolutionState.AVAILABLE)
                            localPathSnapshots[animeId] = LocalPathSnapshot(destination.absolutePath)
                            synchronized(memoryCache) { memoryCache.put(cacheKey(animeId, destination.absolutePath), bitmap) }
                            return DownloadResult.INSTALLED
                        }
                    } finally {
                        connection.disconnect()
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: IOException) {
                    lastFailure = if (exception.isNetworkFailure()) {
                        DownloadResult.NETWORK_ERROR
                    } else {
                        DownloadResult.TEMPORARY_ERROR
                    }
                    Log.w(TAG, "Cover download attempt ${attempt + 1} failed for $animeId", exception)
                }
                if (attempt + 1 < MAX_DOWNLOAD_ATTEMPTS) delay(RETRY_DELAY_MS * (attempt + 1))
            }
            lastFailure
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.w(TAG, "Temporary cover download failure for $animeId", exception)
            DownloadResult.TEMPORARY_ERROR
        } finally {
            if (temporary.exists() && !temporary.delete()) Log.w(TAG, "Unable to remove temporary cover for $animeId")
        }
    }

    private suspend fun installCover(animeId: String, source: File) {
        val destination = coverFile(animeId)
        destination.parentFile?.mkdirs()
        val temporary = File.createTempFile("${destination.nameWithoutExtension}-", ".part", destination.parentFile)
        try {
            source.inputStream().use { input -> temporary.outputStream().use(input::copyTo) }
            val bitmap = BitmapFactory.decodeFile(temporary.absolutePath)
                ?: throw IOException("Cover source is not a decodable image")
            moveAtomically(temporary, destination)
            animeRepository.updateLocalCover(animeId, destination.absolutePath, CoverResolutionState.AVAILABLE)
            localPathSnapshots[animeId] = LocalPathSnapshot(destination.absolutePath)
            synchronized(memoryCache) { memoryCache.put(cacheKey(animeId, destination.absolutePath), bitmap) }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private suspend fun installBundledCover(anime: Anime): Boolean {
        val names = listOfNotNull(
            "local_covers/${anime.id}.jpg",
            "local_covers/${anime.id}.jpeg",
            "local_covers/${anime.id}.png",
            anime.malId?.let { "local_covers/$it.jpg" },
            anime.malId?.let { "local_covers/$it.jpeg" },
            anime.malId?.let { "local_covers/$it.png" }
        )
        names.forEach { name ->
            try {
                val temporary = File.createTempFile("bundled-", ".part", appContext.cacheDir)
                try {
                    appContext.assets.open(name).use { input -> temporary.outputStream().use(input::copyTo) }
                    installCover(anime.id, temporary)
                    return true
                } finally {
                    temporary.delete()
                }
            } catch (_: java.io.FileNotFoundException) {
                // Try the next explicit bundled filename.
            }
        }
        return false
    }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val connection = openHttps(url)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        return try {
            connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            if (connection.responseCode !in 200..299) throw HttpStatusException(connection.responseCode)
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttps(url: String): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true)) { "Only HTTPS cover URLs are allowed" }
        return (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "RankOffline/1.0")
        }
    }

    private fun isValidImage(file: File): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    private fun coverDirectory() = File(appContext.filesDir, "covers")
    private fun legacyCacheFile(url: String) = File(coverDirectory(), "${url.hashCode()}.jpg")
    private fun coverFile(animeId: String) =
        File(coverDirectory(), "${animeId.toByteArray().sha256()}.jpg")

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun IOException.isNetworkFailure(): Boolean =
        this is UnknownHostException || this is ConnectException || this is SocketException ||
            this is SocketTimeoutException || cause is UnknownHostException || cause is ConnectException ||
            cause is SocketException || cause is SocketTimeoutException

    private fun cacheKey(animeId: String, path: String) = "$animeId:$path"
    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private sealed interface LookupResult {
        data class Found(val url: String) : LookupResult
        data object NotFound : LookupResult
        data object NetworkError : LookupResult
        data object TemporaryError : LookupResult
    }

    private enum class DownloadResult { INSTALLED, NOT_FOUND, NETWORK_ERROR, TEMPORARY_ERROR }
    private data class LocalPathSnapshot(val path: String?)
    private class HttpStatusException(code: Int) : IOException("HTTP $code")
    private companion object {
        const val TAG = "CoverRepository"
        const val MAX_DOWNLOAD_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 500L
    }
}
