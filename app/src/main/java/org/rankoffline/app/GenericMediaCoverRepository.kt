package org.rankoffline.app

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

enum class MediaCoverTransferResult { DOWNLOADED, NOT_FOUND, NETWORK_ERROR, TEMPORARY_ERROR }

fun interface MediaCoverDownloader {
    suspend fun download(remoteUri: URI, destination: File): MediaCoverTransferResult
}

class SecureMediaCoverDownloader : MediaCoverDownloader {
    override suspend fun download(remoteUri: URI, destination: File): MediaCoverTransferResult =
        withContext(Dispatchers.IO) {
            val expectedHost = validatedHost(remoteUri) ?: return@withContext MediaCoverTransferResult.NOT_FOUND
            var lastFailure = MediaCoverTransferResult.TEMPORARY_ERROR
            repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
                currentCoroutineContext().ensureActive()
                if (destination.exists()) destination.delete()
                try {
                    when (val result = downloadOnce(remoteUri, expectedHost, destination)) {
                        MediaCoverTransferResult.DOWNLOADED -> {
                            lastFailure = result
                            return@withContext result
                        }
                        MediaCoverTransferResult.NOT_FOUND -> return@withContext result
                        MediaCoverTransferResult.NETWORK_ERROR,
                        MediaCoverTransferResult.TEMPORARY_ERROR -> lastFailure = result
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: IOException) {
                    lastFailure = if (exception.isNetworkFailure()) {
                        MediaCoverTransferResult.NETWORK_ERROR
                    } else {
                        MediaCoverTransferResult.TEMPORARY_ERROR
                    }
                    Log.w(TAG, "Cover download attempt ${attempt + 1} failed", exception)
                } finally {
                    if (lastFailure != MediaCoverTransferResult.DOWNLOADED && destination.exists()) {
                        destination.delete()
                    }
                }
                if (attempt + 1 < MAX_DOWNLOAD_ATTEMPTS) delay(RETRY_DELAY_MS * (attempt + 1))
            }
            lastFailure
        }

    private suspend fun downloadOnce(
        initialUri: URI,
        expectedHost: String,
        destination: File
    ): MediaCoverTransferResult {
        var currentUri = initialUri
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!isAllowedUri(currentUri, expectedHost)) return MediaCoverTransferResult.NOT_FOUND
            val connection = (currentUri.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "image/*")
                setRequestProperty("User-Agent", "RankOffline/1.0")
            }
            try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE) {
                    return MediaCoverTransferResult.NOT_FOUND
                }
                if (code in REDIRECT_CODES) {
                    if (redirects++ >= MAX_REDIRECTS) return MediaCoverTransferResult.TEMPORARY_ERROR
                    val location = connection.getHeaderField("Location")
                        ?: return MediaCoverTransferResult.TEMPORARY_ERROR
                    val redirected = runCatching { currentUri.resolve(location) }.getOrNull()
                        ?: return MediaCoverTransferResult.TEMPORARY_ERROR
                    if (!isAllowedUri(redirected, expectedHost)) return MediaCoverTransferResult.NOT_FOUND
                    currentUri = redirected
                    continue
                }
                if (code == 429 || code == HttpURLConnection.HTTP_UNAVAILABLE) {
                    val retrySeconds = connection.getHeaderField("Retry-After")
                        ?.toLongOrNull()
                        ?.coerceIn(0L, MAX_RETRY_AFTER_SECONDS)
                        ?: 0L
                    if (retrySeconds > 0L) delay(retrySeconds * 1_000L)
                    return MediaCoverTransferResult.TEMPORARY_ERROR
                }
                if (code !in 200..299) return MediaCoverTransferResult.TEMPORARY_ERROR
                if (connection.contentLengthLong > MAX_COVER_BYTES) {
                    return MediaCoverTransferResult.TEMPORARY_ERROR
                }
                connection.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_COVER_BYTES) throw CoverTooLargeException()
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
                return MediaCoverTransferResult.DOWNLOADED
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun validatedHost(uri: URI): String? {
        val host = uri.host?.lowercase() ?: return null
        if (!isAllowedUri(uri, host)) return null
        val addresses = InetAddress.getAllByName(host)
        if (addresses.isEmpty() || addresses.any { address ->
                address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                    address.isSiteLocalAddress || address.isMulticastAddress
            }
        ) return null
        return host
    }

    private fun isAllowedUri(uri: URI, expectedHost: String): Boolean =
        uri.host?.equals(expectedHost, ignoreCase = true) == true && isSafeHttpsUri(uri, setOf(expectedHost))

    private fun IOException.isNetworkFailure(): Boolean =
        this is UnknownHostException || this is ConnectException || this is SocketException ||
            this is SocketTimeoutException || cause is UnknownHostException || cause is ConnectException ||
            cause is SocketException || cause is SocketTimeoutException

    private class CoverTooLargeException : IOException("Cover exceeds maximum download size")

    companion object {
        const val MAX_COVER_BYTES = 15L * 1024L * 1024L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_DOWNLOAD_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 500L
        private const val MAX_RETRY_AFTER_SECONDS = 5L
        private const val MAX_REDIRECTS = 3
        private const val TAG = "MediaCoverDownloader"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

class GenericMediaCoverRepository(
    context: Context,
    database: AnimeDatabase,
    private val provider: MediaCoverProvider = DefaultMediaCoverProvider(),
    private val downloader: MediaCoverDownloader = SecureMediaCoverDownloader(),
    private val animeResolver: AnimeMediaCoverResolver = DefaultAnimeMediaCoverResolver(),
    manifestProvider: (MediaType) -> PackManifest? = { null }
) : CoverObservationSource {
    private val appContext = context.applicationContext
    private val dao = database.mediaDao()
    private val groupDao = database.mediaGroupDao()
    private val storage = CoverStorage(appContext)
    private val declarativeProvider = DeclarativePackCoverProvider(manifestProvider)
    private val inProgress = ConcurrentHashMap.newKeySet<String>()
    private val downloadStates = ConcurrentHashMap<String, MutableStateFlow<CoverDownloadState>>()

    fun resolvedRemoteUrl(item: MediaItem): String? = (
        declarativeProvider.resolve(item) ?: provider.resolve(item)
            .takeUnless { declarativeProvider.hasDeclaredSources(item.mediaType) }
        )?.toASCIIString()

    override fun observeLocalCoverPath(mediaId: String): Flow<String?> =
        dao.observeLocalCoverPath(mediaId).distinctUntilChanged()

    override fun observeDownloadState(mediaId: String): StateFlow<CoverDownloadState> =
        downloadStates.getOrPut(mediaId) { MutableStateFlow(CoverDownloadState.IDLE) }.asStateFlow()

    /** Clears only transient state owned by one pack; Room remains the source of cover paths. */
    fun invalidatePackRuntimeState(mediaType: MediaType) {
        // Canonical provider IDs are intentionally stable and are not required to encode their
        // owner (Anime uses mal:/anilist:). Pack replacement is rare, so clearing this small
        // process-local map is safer than guessing ownership from an ID prefix.
        inProgress.clear()
        downloadStates.values.forEach { it.value = CoverDownloadState.IDLE }
        downloadStates.clear()
    }

    fun observeDownloadedCoverCount(mediaType: MediaType): Flow<Int> = combine(
        dao.observeLocalCoverPaths(mediaType.persistedValue),
        groupDao.observeLocalCoverPaths(mediaType.persistedValue)
    ) { itemPaths, groupPaths ->
        (itemPaths + groupPaths)
            .map(::File)
            .filter { file -> storage.isRemovable(file, mediaType) && isValidImage(file) }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .size
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun ratedCoverItems(mediaType: MediaType): List<MediaItem> = withContext(Dispatchers.IO) {
        dao.ratedCoverItems(mediaType.persistedValue, mediaType.toPackId().value)
            .map(MediaItemEntity::toDomain)
    }

    suspend fun topPopularCoverItems(
        mediaType: MediaType,
        limit: Int,
        oneCoverPerGroup: Boolean = false
    ): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val groupType = mediaType.genericGroupType()
            val items = if (oneCoverPerGroup && groupType != null) {
                groupDao.topLogicalCoverItems(mediaType.persistedValue, groupType, limit)
            } else {
                dao.topPopularCoverItems(mediaType.persistedValue, limit)
            }
            items.map(MediaItemEntity::toDomain)
        }

    suspend fun downloadCover(mediaId: String): CoverDownloadResult = withContext(Dispatchers.IO) {
        val state = downloadStates.getOrPut(mediaId) { MutableStateFlow(CoverDownloadState.IDLE) }
        val entity = dao.item(mediaId) ?: return@withContext CoverDownloadResult.NotFound
        val mediaType = genericMediaType(entity.mediaType) ?: return@withContext CoverDownloadResult.NotFound
        val destination = coverFile(mediaType, mediaId)
        val existing = entity.localCoverPath?.let(::File)
        if (existing != null && isValidImage(existing) &&
            (storage.isDownloaded(existing, mediaType) || storage.isBundled(existing, mediaType))
        ) {
            if (entity.coverResolutionState != CoverResolutionState.AVAILABLE.name) {
                dao.updateMediaCover(mediaId, existing.absolutePath, CoverResolutionState.AVAILABLE.name)
            }
            return@withContext CoverDownloadResult.AlreadyAvailable
        }
        if (!entity.localCoverPath.isNullOrBlank() || entity.coverResolutionState == CoverResolutionState.AVAILABLE.name) {
            if (existing != null && storage.isDownloaded(existing, mediaType) && existing.exists()) existing.delete()
            dao.updateMediaCover(mediaId, null, CoverResolutionState.UNKNOWN.name)
        }

        if (!inProgress.add(mediaId)) return@withContext CoverDownloadResult.AlreadyInProgress

        destination.parentFile?.let { directory ->
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
                inProgress.remove(mediaId)
                return@withContext CoverDownloadResult.TemporaryError
            }
        }
        state.value = CoverDownloadState.DOWNLOADING
        var temporary: File? = null
        var installed = false
        try {
            // Resolution is part of the download operation. In particular, normalized Anime
            // entries intentionally carry stable provider IDs instead of requiring a URL in the
            // pack, so provider failures must reach the same recoverable error path as HTTP.
            val item = entity.toDomain()
            val declarativeUris = declarativeProvider.resolveCandidates(item)
            val remoteUris = declarativeUris.ifEmpty {
                if (declarativeProvider.hasDeclaredSources(mediaType)) emptyList()
                else provider.resolveCandidates(item)
            }.ifEmpty {
                if (mediaType == MediaType.ANIME) animeResolver.resolve(entity.toDomain()) else emptyList()
            }
            if (remoteUris.isEmpty()) {
                dao.updateMediaCover(mediaId, null, CoverResolutionState.NOT_FOUND.name)
                return@withContext CoverDownloadResult.NotFound
            }
            val downloadFile = File.createTempFile(
                destination.nameWithoutExtension + "-",
                ".part",
                destination.parentFile
            )
            temporary = downloadFile
            var invalidImageReceived = false
            var networkFailure = false
            var temporaryFailure = false
            for (remoteUri in remoteUris) {
                when (downloader.download(remoteUri, downloadFile)) {
                    MediaCoverTransferResult.DOWNLOADED -> {
                        if (!isValidImage(downloadFile)) {
                            invalidImageReceived = true
                            downloadFile.delete()
                            continue
                        }
                        moveAtomically(downloadFile, destination)
                        installed = true
                        if (dao.updateMediaCover(
                                mediaId,
                                destination.absolutePath,
                                CoverResolutionState.AVAILABLE.name
                            ) != 1
                        ) {
                            throw IOException("Media item disappeared while installing its cover")
                        }
                        installed = false
                        return@withContext CoverDownloadResult.Downloaded
                    }
                    MediaCoverTransferResult.NOT_FOUND -> Unit
                    MediaCoverTransferResult.NETWORK_ERROR -> networkFailure = true
                    MediaCoverTransferResult.TEMPORARY_ERROR -> temporaryFailure = true
                }
            }
            if (invalidImageReceived || temporaryFailure) return@withContext CoverDownloadResult.TemporaryError
            if (networkFailure) return@withContext CoverDownloadResult.NetworkError
            dao.updateMediaCover(mediaId, null, CoverResolutionState.NOT_FOUND.name)
            CoverDownloadResult.NotFound
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            Log.w(TAG, "operation=item-cover mediaId=$mediaId failed=${exception.message}", exception)
            if (exception.isNetworkFailure()) CoverDownloadResult.NetworkError
            else CoverDownloadResult.TemporaryError
        } catch (exception: Exception) {
            Log.w(TAG, "operation=item-cover mediaId=$mediaId failed=${exception.message}", exception)
            CoverDownloadResult.TemporaryError
        } finally {
            if (temporary?.exists() == true) temporary.delete()
            if (installed && destination.exists()) destination.delete()
            inProgress.remove(mediaId)
            state.value = CoverDownloadState.IDLE
        }
    }

    suspend fun removeLocalCover(mediaId: String): Boolean = withContext(Dispatchers.IO) {
        val entity = dao.item(mediaId) ?: return@withContext false
        val mediaType = genericMediaType(entity.mediaType) ?: return@withContext false
        val stored = entity.localCoverPath?.let(::File)
        val deletable = stored != null && storage.isRemovable(stored, mediaType)
        if (!deletable) return@withContext false
        if (deletable && stored?.exists() == true && !stored.delete()) {
            return@withContext false
        }
        dao.updateMediaCover(mediaId, null, CoverResolutionState.UNKNOWN.name) == 1
    }

    suspend fun clearDownloadedCovers(mediaType: MediaType): Int = withContext(Dispatchers.IO) {
        val items = dao.itemsWithLocalCovers(mediaType.persistedValue)
        var cleared = 0
        items.forEach { entity ->
            val stored = entity.localCoverPath?.let(::File)
            if (stored == null || !storage.isRemovable(stored, mediaType)) return@forEach
            if (stored.exists() && !stored.delete()) {
                Log.w(TAG, "Unable to remove generic cover ${stored.name}")
                return@forEach
            }
            dao.updateMediaCover(entity.id, null, CoverResolutionState.UNKNOWN.name)
            cleared++
        }
        cleared
    }

    suspend fun clearDownloadedCoverFiles(mediaType: MediaType): Boolean = withContext(Dispatchers.IO) {
        storage.clearCoverFiles(mediaType)
    }

    private fun coverFile(mediaType: MediaType, mediaId: String): File =
        storage.downloadedItem(mediaType, mediaId)

    private fun genericMediaType(value: String): MediaType? =
        MediaType.entries.firstOrNull { it.persistedValue == value }

    private fun isValidImage(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L || file.length() > SecureMediaCoverDownloader.MAX_COVER_BYTES) {
            return false
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val pixels = options.outWidth.toLong() * options.outHeight.toLong()
        return options.outWidth in 1..MAX_IMAGE_DIMENSION &&
            options.outHeight in 1..MAX_IMAGE_DIMENSION &&
            pixels in 1..MAX_IMAGE_PIXELS
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun IOException.isNetworkFailure(): Boolean =
        this is UnknownHostException || this is ConnectException || this is SocketException ||
            this is SocketTimeoutException || cause is UnknownHostException || cause is ConnectException ||
            cause is SocketException || cause is SocketTimeoutException

    companion object {
        private const val MAX_IMAGE_DIMENSION = 20_000
        private const val MAX_IMAGE_PIXELS = 100_000_000L
        private const val TAG = "RankOfflineCover"
    }
}

/** Explicit, on-demand cover operations for TMDB collection and season groups. */
class GenericMediaGroupCoverRepository(
    context: Context,
    database: AnimeDatabase,
    private val provider: MediaCoverProvider = DefaultMediaCoverProvider(),
    private val downloader: MediaCoverDownloader = SecureMediaCoverDownloader(),
    manifestProvider: (MediaType) -> PackManifest? = { null }
) : CoverObservationSource {
    private val appContext = context.applicationContext
    private val dao = database.mediaGroupDao()
    private val storage = CoverStorage(appContext)
    private val declarativeProvider = DeclarativePackCoverProvider(manifestProvider)
    private val inProgress = ConcurrentHashMap.newKeySet<String>()
    private val downloadStates = ConcurrentHashMap<String, MutableStateFlow<CoverDownloadState>>()

    override fun observeLocalCoverPath(mediaId: String): Flow<String?> =
        dao.observeLocalCoverPath(mediaId).distinctUntilChanged()

    override fun observeDownloadState(mediaId: String): StateFlow<CoverDownloadState> =
        downloadStates.getOrPut(mediaId) { MutableStateFlow(CoverDownloadState.IDLE) }.asStateFlow()

    fun invalidatePackRuntimeState(mediaType: MediaType) {
        inProgress.clear()
        downloadStates.values.forEach { it.value = CoverDownloadState.IDLE }
        downloadStates.clear()
    }

    suspend fun downloadCover(groupId: String): CoverDownloadResult = withContext(Dispatchers.IO) {
        val state = downloadStates.getOrPut(groupId) { MutableStateFlow(CoverDownloadState.IDLE) }
        val entity = dao.group(groupId) ?: return@withContext CoverDownloadResult.NotFound
        val mediaType = MediaType.entries.firstOrNull { it.persistedValue == entity.mediaType }
            ?: return@withContext CoverDownloadResult.NotFound
        val destination = coverFile(mediaType, groupId)
        val existing = entity.localCoverPath?.let(::File)
        if (existing != null && validCoverImage(existing) &&
            (storage.isDownloaded(existing, mediaType) || storage.isBundled(existing, mediaType))
        ) {
            if (entity.coverResolutionState != CoverResolutionState.AVAILABLE.name) {
                dao.updateGroupCover(groupId, existing.absolutePath, CoverResolutionState.AVAILABLE.name)
            }
            return@withContext CoverDownloadResult.AlreadyAvailable
        }
        if (!entity.localCoverPath.isNullOrBlank() || entity.coverResolutionState == CoverResolutionState.AVAILABLE.name) {
            if (existing != null && storage.isDownloaded(existing, mediaType) && existing.exists()) existing.delete()
            dao.updateGroupCover(groupId, null, CoverResolutionState.UNKNOWN.name)
        }
        if (!inProgress.add(groupId)) return@withContext CoverDownloadResult.AlreadyInProgress
        destination.parentFile?.let {
            if (!it.exists() && !it.mkdirs() && !it.isDirectory) {
                inProgress.remove(groupId)
                return@withContext CoverDownloadResult.TemporaryError
            }
        }
        state.value = CoverDownloadState.DOWNLOADING
        var temporary: File? = null
        var installed = false
        try {
            val item = entity.toDomain().asCoverItem()
            val declarativeUris = declarativeProvider.resolveCandidates(item)
            val remoteUris = declarativeUris.ifEmpty {
                if (declarativeProvider.hasDeclaredSources(mediaType)) emptyList()
                else provider.resolveCandidates(item)
            }
            if (remoteUris.isEmpty()) {
                dao.updateGroupCover(groupId, null, CoverResolutionState.NOT_FOUND.name)
                return@withContext CoverDownloadResult.NotFound
            }
            val downloadFile = File.createTempFile(
                destination.nameWithoutExtension + "-", ".part", destination.parentFile
            )
            temporary = downloadFile
            var invalidImageReceived = false
            var networkFailure = false
            var temporaryFailure = false
            remoteUris.forEach { remoteUri ->
                when (downloader.download(remoteUri, downloadFile)) {
                    MediaCoverTransferResult.DOWNLOADED -> {
                        if (!validCoverImage(downloadFile)) {
                            invalidImageReceived = true
                            downloadFile.delete()
                            return@forEach
                        }
                        moveCoverAtomically(downloadFile, destination)
                        installed = true
                        if (dao.updateGroupCover(groupId, destination.absolutePath, CoverResolutionState.AVAILABLE.name) != 1) {
                            throw IOException("Media group disappeared while installing its cover")
                        }
                        installed = false
                        return@withContext CoverDownloadResult.Downloaded
                    }
                    MediaCoverTransferResult.NOT_FOUND -> Unit
                    MediaCoverTransferResult.NETWORK_ERROR -> networkFailure = true
                    MediaCoverTransferResult.TEMPORARY_ERROR -> temporaryFailure = true
                }
            }
            if (invalidImageReceived || temporaryFailure) return@withContext CoverDownloadResult.TemporaryError
            if (networkFailure) return@withContext CoverDownloadResult.NetworkError
            dao.updateGroupCover(groupId, null, CoverResolutionState.NOT_FOUND.name)
            CoverDownloadResult.NotFound
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            Log.w(TAG, "operation=group-cover groupId=$groupId failed=${exception.message}", exception)
            if (exception.isNetworkFailureForCover()) CoverDownloadResult.NetworkError
            else CoverDownloadResult.TemporaryError
        } catch (exception: Exception) {
            Log.w(TAG, "operation=group-cover groupId=$groupId failed=${exception.message}", exception)
            CoverDownloadResult.TemporaryError
        } finally {
            if (temporary?.exists() == true) temporary.delete()
            if (installed && destination.exists()) destination.delete()
            inProgress.remove(groupId)
            state.value = CoverDownloadState.IDLE
        }
    }

    suspend fun removeLocalCover(groupId: String): Boolean = withContext(Dispatchers.IO) {
        val entity = dao.group(groupId) ?: return@withContext false
        val mediaType = MediaType.entries.firstOrNull { it.persistedValue == entity.mediaType }
            ?: return@withContext false
        val stored = entity.localCoverPath?.let(::File)
        if (stored == null || !storage.isRemovable(stored, mediaType)) return@withContext false
        if (stored.exists() && !stored.delete()) return@withContext false
        dao.updateGroupCover(groupId, null, CoverResolutionState.UNKNOWN.name) == 1
    }

    suspend fun clearDownloadedCovers(mediaType: MediaType): Int = withContext(Dispatchers.IO) {
        val groups = dao.groupsWithLocalCovers(mediaType.persistedValue)
        var cleared = 0
        groups.forEach { entity ->
            val stored = entity.localCoverPath?.let(::File)
            if (stored == null || !storage.isRemovable(stored, mediaType)) return@forEach
            if (stored.exists() && !stored.delete()) {
                Log.w(TAG, "Unable to remove group cover ${stored.name}")
                return@forEach
            }
            dao.updateGroupCover(entity.id, null, CoverResolutionState.UNKNOWN.name)
            cleared++
        }
        cleared
    }

    private fun MediaGroup.asCoverItem() = MediaItem(
        id = id,
        mediaType = mediaType,
        provider = provider,
        providerId = providerGroupId,
        title = displayTitle,
        remoteCoverRef = remoteCoverRef
    )

    private fun coverFile(mediaType: MediaType, groupId: String): File =
        storage.downloadedGroup(mediaType, groupId)

    private companion object { const val TAG = "RankOfflineCover" }
}

private fun IOException.isNetworkFailureForCover(): Boolean =
    this is UnknownHostException || this is ConnectException || this is SocketException ||
        this is SocketTimeoutException || cause is UnknownHostException || cause is ConnectException ||
        cause is SocketException || cause is SocketTimeoutException

private fun validCoverImage(file: File): Boolean {
    if (!file.isFile || file.length() <= 0L || file.length() > SecureMediaCoverDownloader.MAX_COVER_BYTES) return false
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    val pixels = options.outWidth.toLong() * options.outHeight.toLong()
    return options.outWidth in 1..20_000 && options.outHeight in 1..20_000 && pixels in 1..100_000_000L
}

private fun moveCoverAtomically(source: File, destination: File) {
    try {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
