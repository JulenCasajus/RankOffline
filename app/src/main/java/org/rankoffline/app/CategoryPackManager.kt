package org.rankoffline.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class CategoryPackOrigin { BUNDLED, DOWNLOADED, UNAVAILABLE }

enum class CategoryPackRuntimeStatus {
    FILES_MISSING,
    FILES_INSTALLED,
    INITIALIZING,
    READY,
    ERROR
}

enum class CategoryPackInstallPhase {
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    IMPORTING_CATALOG,
    IMPORTING_GROUPS,
    FINALIZING
}

data class CategoryPackInstallProgress(
    val phase: CategoryPackInstallPhase,
    val downloadedBytes: Long,
    val totalDownloadBytes: Long,
    val totalInstalledBytes: Long = totalDownloadBytes
) {
    val fraction: Float
        get() = if (totalDownloadBytes <= 0L) 0f else
            (downloadedBytes.toDouble() / totalDownloadBytes).coerceIn(0.0, 1.0).toFloat()

    val displayedInstalledBytes: Long
        get() = installedSizeEquivalent(downloadedBytes, totalDownloadBytes, totalInstalledBytes)
}

internal fun installedSizeEquivalent(
    downloadedArchiveBytes: Long,
    totalArchiveBytes: Long,
    declaredInstalledBytes: Long
): Long {
    if (totalArchiveBytes <= 0L || declaredInstalledBytes <= 0L) return 0L
    if (downloadedArchiveBytes >= totalArchiveBytes) return declaredInstalledBytes
    val fraction = (downloadedArchiveBytes.coerceAtLeast(0L).toDouble() / totalArchiveBytes)
        .coerceIn(0.0, 1.0)
    return (fraction * declaredInstalledBytes.toDouble())
        .coerceIn(0.0, declaredInstalledBytes.toDouble())
        .toLong()
}

data class CategoryPackState(
    val mediaType: MediaType,
    val origin: CategoryPackOrigin,
    val catalogBytes: Long,
    val downloadedCoverBytes: Long,
    val reclaimableBytes: Long,
    val downloadBytes: Long? = null,
    val declaredInstalledBytes: Long? = null,
    val installProgress: CategoryPackInstallProgress? = null,
    val downloadConfigured: Boolean = false,
    val runtimeStatus: CategoryPackRuntimeStatus = when (origin) {
        CategoryPackOrigin.UNAVAILABLE -> CategoryPackRuntimeStatus.FILES_MISSING
        else -> CategoryPackRuntimeStatus.READY
    }
) {
    val available: Boolean
        get() = origin != CategoryPackOrigin.UNAVAILABLE &&
            runtimeStatus == CategoryPackRuntimeStatus.READY && installProgress == null
    val installedBytes: Long get() = catalogBytes + downloadedCoverBytes
    val finalStorageBytes: Long
        get() = if (origin == CategoryPackOrigin.UNAVAILABLE) {
            declaredInstalledBytes ?: 0L
        } else {
            installedBytes
        }
    val canDelete: Boolean
        get() = origin == CategoryPackOrigin.DOWNLOADED &&
            runtimeStatus != CategoryPackRuntimeStatus.INITIALIZING && installProgress == null
    val canDownload: Boolean
        get() = origin == CategoryPackOrigin.UNAVAILABLE && downloadBytes != null &&
            downloadConfigured && installProgress == null
    val canRetry: Boolean
        get() = origin != CategoryPackOrigin.UNAVAILABLE &&
            runtimeStatus == CategoryPackRuntimeStatus.ERROR && installProgress == null
}

data class CategoryDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val currentMediaType: MediaType? = null,
    val currentPosition: Int = 0,
    val transferCount: Int = 0
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f
        else (downloadedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
}

internal fun aggregateCategoryDownloadProgress(
    transfers: Collection<Pair<Long, Long>>,
    currentMediaType: MediaType? = null,
    currentPosition: Int = 0,
    transferCount: Int = transfers.size
): CategoryDownloadProgress = CategoryDownloadProgress(
    downloadedBytes = transfers.sumOf { (downloaded, total) -> downloaded.coerceIn(0L, total.coerceAtLeast(0L)) },
    totalBytes = transfers.sumOf { (_, total) -> total.coerceAtLeast(0L) },
    currentMediaType = currentMediaType,
    currentPosition = currentPosition,
    transferCount = transferCount
)

data class CategoryPackDescriptor(
    val installManifest: CategoryPackInstallManifest,
    val manifest: PackManifest,
    val formatVersion: Int,
    val minimumDatabaseVersion: Int,
    val archiveName: String,
    val archiveBytes: Long,
    val archiveSha256: String
)

enum class CategoryPackDownloadResult { INSTALLED, UNAVAILABLE, NETWORK_ERROR, INVALID_PACK }

fun interface CategoryPackArchiveDownloader {
    suspend fun download(
        descriptor: CategoryPackDescriptor,
        destination: File,
        onProgress: (Long) -> Unit
    ): CategoryPackDownloadResult
}

/** HTTPS-only downloader. Redirects are accepted only on the configured host. */
class SecureCategoryPackArchiveDownloader(baseUrl: String) : CategoryPackArchiveDownloader {
    private val baseUri = baseUrl.trim().takeIf(String::isNotEmpty)
        ?.let { if (it.endsWith('/')) it else "$it/" }
        ?.let(URI::create)
        ?.takeIf { it.scheme == "https" && !it.host.isNullOrBlank() }

    val configured: Boolean get() = baseUri != null

    override suspend fun download(
        descriptor: CategoryPackDescriptor,
        destination: File,
        onProgress: (Long) -> Unit
    ): CategoryPackDownloadResult = withContext(Dispatchers.IO) {
        val base = baseUri ?: return@withContext CategoryPackDownloadResult.UNAVAILABLE
        var current = base.resolve(descriptor.archiveName)
        val expectedHost = base.host.lowercase()
        fun approvedHost(host: String?): Boolean {
            val normalized = host?.lowercase() ?: return false
            return normalized == expectedHost ||
                (expectedHost == "github.com" && normalized.endsWith(".githubusercontent.com"))
        }
        var redirects = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (current.scheme != "https" || !approvedHost(current.host)) {
                    return@withContext CategoryPackDownloadResult.INVALID_PACK
                }
                val connection = (current.toURL().openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Accept", "application/zip")
                    setRequestProperty("User-Agent", "RankOffline/${BuildConfig.VERSION_NAME}")
                }
                try {
                    when (val code = connection.responseCode) {
                        in 200..299 -> {
                            if (connection.contentLengthLong > descriptor.archiveBytes) {
                                return@withContext CategoryPackDownloadResult.INVALID_PACK
                            }
                            var total = 0L
                            connection.inputStream.use { input ->
                                destination.outputStream().buffered().use { output ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        total += read
                                        if (total > descriptor.archiveBytes) {
                                            return@withContext CategoryPackDownloadResult.INVALID_PACK
                                        }
                                        output.write(buffer, 0, read)
                                        onProgress(total)
                                    }
                                }
                            }
                            return@withContext if (
                                total == descriptor.archiveBytes &&
                                destination.sha256() == descriptor.archiveSha256
                            ) CategoryPackDownloadResult.INSTALLED
                            else CategoryPackDownloadResult.INVALID_PACK
                        }
                        in REDIRECT_CODES -> {
                            if (redirects++ >= 3) return@withContext CategoryPackDownloadResult.NETWORK_ERROR
                            val location = connection.getHeaderField("Location")
                                ?: return@withContext CategoryPackDownloadResult.NETWORK_ERROR
                            current = current.resolve(location)
                        }
                        HttpURLConnection.HTTP_NOT_FOUND ->
                            return@withContext CategoryPackDownloadResult.UNAVAILABLE
                        else -> return@withContext if (code in 400..499) {
                            CategoryPackDownloadResult.UNAVAILABLE
                        } else CategoryPackDownloadResult.NETWORK_ERROR
                    }
                } finally {
                    connection.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            CategoryPackDownloadResult.NETWORK_ERROR
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            CategoryPackDownloadResult.NETWORK_ERROR
        }
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/**
 * Owns downloadable catalog files only. APK assets are never reported as reclaimable category
 * storage. Queued downloads are serialized and share one byte-accurate aggregate progress value.
 */
class CategoryPackManager(
    context: Context,
    configuredBaseUrl: String = BuildConfig.CATEGORY_PACK_BASE_URL,
    private val injectedDownloader: CategoryPackArchiveDownloader? = null,
    injectedDescriptors: Map<PackId, CategoryPackDescriptor>? = null,
    private val bundledCatalogsAvailable: Boolean = BuildConfig.BUNDLED_PRODUCTION_CATALOGS
) {
    private val appContext = context.applicationContext
    private val installer = CategoryPackInstaller(File(appContext.filesDir, PACK_ROOT))
    private val registry = PackRegistry(File(appContext.filesDir, PACK_ROOT))
    private val bundledAssetBytes by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (!bundledCatalogsAvailable) emptyMap() else runCatching {
            ZipFile(appContext.applicationInfo.sourceDir).use { apk ->
                MediaType.entries.associateWith { mediaType ->
                    assetNames(mediaType).sumOf { name ->
                        apk.getEntry("assets/$name")?.compressedSize ?: 0L
                    }
                }
            }
        }.getOrElse {
            MediaType.entries.associateWith { mediaType ->
                assetNames(mediaType).sumOf { name ->
                    runCatching { appContext.assets.open(name).use { it.countBytes() } }.getOrDefault(0L)
                }
            }
        }
    }
    private val descriptors = injectedDescriptors ?: loadCategoryPackIndex(appContext)
    private val secureDownloader = SecureCategoryPackArchiveDownloader(configuredBaseUrl)
    private val downloader = injectedDownloader ?: secureDownloader
    private val downloadMutex = Mutex()
    private val lifecycleMutexes = ConcurrentHashMap<PackId, Mutex>()
    private val transferLock = Any()
    private val transfers = linkedMapOf<MediaType, Pair<Long, Long>>()
    private val installProgress = linkedMapOf<MediaType, CategoryPackInstallProgress>()
    private val runtimeStatuses = linkedMapOf<MediaType, CategoryPackRuntimeStatus>()
    private val completedTransfers = mutableSetOf<MediaType>()
    private var activeTransfer: MediaType? = null
    private val _states = MutableStateFlow(emptyMap<MediaType, CategoryPackState>())
    val states: StateFlow<Map<MediaType, CategoryPackState>> = _states.asStateFlow()
    private val _downloadProgress = MutableStateFlow<CategoryDownloadProgress?>(null)
    val downloadProgress: StateFlow<CategoryDownloadProgress?> = _downloadProgress.asStateFlow()

    val downloadConfigured: Boolean get() = injectedDownloader != null || secureDownloader.configured

    suspend fun recoverFilesystem() = withContext(Dispatchers.IO) {
        migrateLegacyPacks()
        descriptors.keys.forEach(installer::recover)
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        registry.refresh(descriptors.mapValues { it.value.archiveBytes })
        _states.value = MediaType.entries.associateWith(::stateFor)
    }

    /**
     * Recovers one installed pack and reconciles its durable files with Room before publishing
     * READY. Startup, manual installation and destructive purge share this per-pack mutex.
     */
    suspend fun recoverRuntime(
        mediaType: MediaType,
        clearMissingCatalog: suspend () -> Unit,
        initializeRuntime: suspend ((CategoryPackInstallPhase) -> Unit) -> CatalogAvailability
    ): CatalogAvailability = lifecycleMutex(mediaType).withLock {
        withContext(Dispatchers.IO) { installer.recover(mediaType) }
        val hasFiles = installer.installedDirectory(mediaType) != null ||
            (bundledAssetBytes[mediaType] ?: 0L) > 0L
        if (!hasFiles) {
            clearMissingCatalog()
            setRuntimeStatus(mediaType, CategoryPackRuntimeStatus.FILES_MISSING)
            refresh()
            return@withLock CatalogAvailability.UNAVAILABLE
        }

        val startedAt = System.nanoTime()
        setRuntimeStatus(mediaType, CategoryPackRuntimeStatus.INITIALIZING)
        updateInstallPhase(mediaType, CategoryPackInstallPhase.IMPORTING_CATALOG)
        val result = try {
            initializeRuntime { phase ->
                if (phase == CategoryPackInstallPhase.IMPORTING_CATALOG ||
                    phase == CategoryPackInstallPhase.IMPORTING_GROUPS
                ) updateInstallPhase(mediaType, phase)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Log.e(
                PACK_LOG_TAG,
                "pack=${mediaType.toPackId().value} operation=startup-recovery phase=import " +
                    "elapsedMs=${elapsedMillis(startedAt)} failed=${exception.javaClass.simpleName}: ${exception.message}",
                exception
            )
            CatalogAvailability.ERROR
        }
        clearInstallProgress(mediaType)
        setRuntimeStatus(
            mediaType,
            if (result == CatalogAvailability.AVAILABLE) CategoryPackRuntimeStatus.READY
            else CategoryPackRuntimeStatus.ERROR
        )
        refresh()
        Log.d(
            PACK_LOG_TAG,
            "pack=${mediaType.toPackId().value} operation=startup-recovery result=$result " +
                "elapsedMs=${elapsedMillis(startedAt)}"
        )
        result
    }

    suspend fun downloadAndInstall(
        mediaType: MediaType,
        importInstalledPack: suspend ((CategoryPackInstallPhase) -> Unit) -> Boolean = { true },
        finalizeRuntime: suspend () -> Unit = {}
    ): CategoryPackDownloadResult {
        val packId = mediaType.toPackId()
        val descriptor = descriptors[packId] ?: return CategoryPackDownloadResult.UNAVAILABLE
        if (descriptor.formatVersion != PackManifestParser.SCHEMA_VERSION ||
            descriptor.minimumDatabaseVersion > ROOM_DATABASE_VERSION ||
            !isCompatibleAppVersion(descriptor.manifest.minRankOfflineVersion, BuildConfig.PACK_API_VERSION)
        ) {
            return CategoryPackDownloadResult.INVALID_PACK
        }
        registerTransfer(
            mediaType,
            descriptor.archiveBytes,
            descriptor.installManifest.installedBytes
        )
        return try {
            downloadMutex.withLock {
                lifecycleMutex(mediaType).withLock {
                startTransfer(mediaType)
                setRuntimeStatus(mediaType, CategoryPackRuntimeStatus.INITIALIZING)
                val tempRoot = File(appContext.cacheDir, "category-pack-downloads").apply { mkdirs() }
                val archive = File(tempRoot, "${mediaType.persistedValue}.part")
                archive.delete()
                var promoted = false
                try {
                    val result = downloader.download(descriptor, archive) { downloaded ->
                        updateTransfer(mediaType, downloaded, descriptor.archiveBytes)
                    }
                    if (result != CategoryPackDownloadResult.INSTALLED) return@withLock result
                    updateInstallPhase(mediaType, CategoryPackInstallPhase.VERIFYING)
                    ZipFile(archive).use { zip ->
                        val expected = descriptor.installManifest.files.mapTo(mutableSetOf()) { it.name }
                            .apply { add(CategoryPackInstaller.PACK_MANIFEST) }
                        val actual = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
                        require(actual.size == expected.size && actual.toSet() == expected) {
                            "Category pack archive file list mismatch"
                        }
                        val archiveManifest = zip.getInputStream(
                            requireNotNull(zip.getEntry(CategoryPackInstaller.PACK_MANIFEST))
                        ).bufferedReader().use { it.readText() }
                        require(PackManifestParser.parse(archiveManifest) == descriptor.manifest) {
                            "Pack manifest does not match the trusted release index"
                        }
                        updateInstallPhase(mediaType, CategoryPackInstallPhase.INSTALLING)
                        installer.install(
                            descriptor.installManifest,
                            retainPreviousUntilCommit = true
                        ) { spec ->
                            zip.getInputStream(requireNotNull(zip.getEntry(spec.name)))
                        }
                        promoted = true
                    }
                    val imported = importInstalledPack { phase ->
                        require(
                            phase == CategoryPackInstallPhase.IMPORTING_CATALOG ||
                                phase == CategoryPackInstallPhase.IMPORTING_GROUPS
                        )
                        updateInstallPhase(mediaType, phase)
                    }
                    if (!imported) {
                        installer.rollbackReplacement(packId)
                        promoted = false
                        recoverPreviousRuntime(mediaType, importInstalledPack)
                        return@withLock CategoryPackDownloadResult.INVALID_PACK
                    }
                    updateInstallPhase(mediaType, CategoryPackInstallPhase.FINALIZING)
                    registry.refresh(descriptors.mapValues { it.value.archiveBytes })
                    finalizeRuntime()
                    installer.commitReplacement(packId)
                    setRuntimeStatus(mediaType, CategoryPackRuntimeStatus.READY)
                    CategoryPackDownloadResult.INSTALLED
                } catch (cancelled: CancellationException) {
                    if (promoted) installer.rollbackReplacement(packId)
                    throw cancelled
                } catch (exception: Exception) {
                    Log.e(
                        PACK_LOG_TAG,
                        "pack=${packId.value} operation=install failed=${exception.javaClass.simpleName}: ${exception.message}",
                        exception
                    )
                    if (promoted) {
                        installer.rollbackReplacement(packId)
                        recoverPreviousRuntime(mediaType, importInstalledPack)
                    } else {
                        setRuntimeStatus(mediaType, CategoryPackRuntimeStatus.ERROR)
                    }
                    CategoryPackDownloadResult.INVALID_PACK
                } finally {
                    archive.delete()
                }
                }
            }
        } finally {
            finishTransfer(mediaType)
            refresh()
        }
    }

    suspend fun deleteInstalledFiles(mediaType: MediaType): Boolean = withContext(Dispatchers.IO) {
        lifecycleMutex(mediaType).withLock { deleteInstalledFilesLocked(mediaType) }
    }

    /** Purges Room/preferences and files as one serialized lifecycle operation for this pack. */
    suspend fun purgePack(
        mediaType: MediaType,
        purgeRuntimeData: suspend () -> Unit,
        finalizeRuntime: suspend () -> Unit
    ): Boolean = lifecycleMutex(mediaType).withLock {
        setRuntimeStatus(mediaType, CategoryPackRuntimeStatus.INITIALIZING)
        return@withLock try {
            purgeRuntimeData()
            val deleted = withContext(Dispatchers.IO) { deleteInstalledFilesLocked(mediaType) }
            setRuntimeStatus(mediaType, CategoryPackRuntimeStatus.FILES_MISSING)
            refresh()
            finalizeRuntime()
            deleted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Log.e(
                PACK_LOG_TAG,
                "pack=${mediaType.toPackId().value} operation=purge failed=${exception.javaClass.simpleName}: ${exception.message}",
                exception
            )
            setRuntimeStatus(mediaType, CategoryPackRuntimeStatus.ERROR)
            refresh()
            false
        }
    }

    private fun deleteInstalledFilesLocked(mediaType: MediaType): Boolean {
        val packId = mediaType.toPackId()
        val packDeleted = installer.deleteInstalledPack(packId)
        val coverRoot = File(appContext.filesDir, "covers")
        val directory = File(coverRoot, mediaType.persistedValue)
        val scopedDeleted = !directory.exists() || directory.deleteRecursively()
        val legacyAnimeDeleted = mediaType != MediaType.ANIME ||
            coverRoot.listFiles().orEmpty().filter(File::isFile).all { it.delete() || !it.exists() }
        val coversDeleted = scopedDeleted && legacyAnimeDeleted
        val packCacheDeleted = listOf(
            File(File(appContext.cacheDir, "packs"), packId.value),
            File(File(appContext.cacheDir, "category-pack-downloads"), "${mediaType.persistedValue}.part")
        ).all { !it.exists() || it.deleteRecursively() }
        return packDeleted && coversDeleted && packCacheDeleted
    }

    /** Data-only fallback for future non-official packs, which do not yet have Room/UI adapters. */
    suspend fun deleteInstalledFiles(packId: PackId): Boolean = withContext(Dispatchers.IO) {
        packId.toOfficialMediaTypeOrNull()?.let { return@withContext deleteInstalledFiles(it) }
        val packDeleted = installer.deleteInstalledPack(packId)
        val coversDeleted = File(File(appContext.filesDir, "covers"), packId.value)
            .let { !it.exists() || it.deleteRecursively() }
        val cacheDeleted = File(File(appContext.cacheDir, "packs"), packId.value)
            .let { !it.exists() || it.deleteRecursively() }
        refresh()
        packDeleted && coversDeleted && cacheDeleted
    }

    fun installedDirectory(mediaType: MediaType): File? = installer.installedDirectory(mediaType)
    fun descriptor(mediaType: MediaType): CategoryPackDescriptor? = descriptors[mediaType.toPackId()]
    fun packDefinition(packId: PackId): PackDefinition? = registry.definition(packId)

    private fun migrateLegacyPacks() {
        val legacyRoot = File(appContext.filesDir, LEGACY_PACK_ROOT)
        if (!legacyRoot.isDirectory) return
        MediaType.entries.forEach { mediaType ->
            val packId = mediaType.toPackId()
            if (installer.installedDirectory(packId) != null) return@forEach
            val legacy = File(legacyRoot, mediaType.persistedValue)
                .takeIf { File(it, CategoryPackInstaller.COMPLETE_MARKER).isFile }
                ?: return@forEach
            val descriptor = descriptors[packId] ?: return@forEach
            val migrated = runCatching {
                installer.install(descriptor.installManifest) { spec ->
                    val source = File(legacy, File(spec.name).name)
                    require(source.isFile) { "Legacy pack is incomplete" }
                    source.inputStream()
                }
            }.isSuccess
            if (migrated) legacy.deleteRecursively()
        }
        if (legacyRoot.listFiles().isNullOrEmpty()) legacyRoot.delete()
    }

    private fun stateFor(mediaType: MediaType): CategoryPackState {
        val installedPack = installer.installedDirectory(mediaType.toPackId())
        val bundledBytes = bundledAssetBytes[mediaType] ?: 0L
        val catalogBytes = installedPack?.recursiveFileBytes() ?: bundledBytes
        val coverBytes = downloadedCoverBytes(mediaType)
        val descriptor = descriptors[mediaType.toPackId()]
        val origin = when {
            installedPack != null -> CategoryPackOrigin.DOWNLOADED
            bundledBytes > 0L -> CategoryPackOrigin.BUNDLED
            else -> CategoryPackOrigin.UNAVAILABLE
        }
        val runtimeStatus = synchronized(transferLock) {
            if (origin == CategoryPackOrigin.UNAVAILABLE) {
                runtimeStatuses[mediaType] = CategoryPackRuntimeStatus.FILES_MISSING
                CategoryPackRuntimeStatus.FILES_MISSING
            } else {
                runtimeStatuses.getOrPut(mediaType) { CategoryPackRuntimeStatus.FILES_INSTALLED }
            }
        }
        return CategoryPackState(
            mediaType = mediaType,
            origin = origin,
            catalogBytes = catalogBytes,
            downloadedCoverBytes = coverBytes,
            reclaimableBytes = coverBytes + if (installedPack != null) catalogBytes else 0L,
            downloadBytes = descriptor?.archiveBytes,
            declaredInstalledBytes = descriptor?.installManifest?.installedBytes,
            installProgress = synchronized(transferLock) { installProgress[mediaType] },
            downloadConfigured = downloadConfigured,
            runtimeStatus = runtimeStatus
        )
    }

    private fun downloadedCoverBytes(mediaType: MediaType): Long {
        val root = File(appContext.filesDir, "covers")
        val scoped = File(root, mediaType.persistedValue).recursiveFileBytes()
        val legacy = if (mediaType == MediaType.ANIME) {
            root.listFiles().orEmpty().filter(File::isFile).sumOf(File::length)
        } else 0L
        return scoped + legacy
    }

    private fun assetNames(mediaType: MediaType): List<String> = when (mediaType) {
        MediaType.ANIME -> listOf(
            "anime_catalog.jsonl.gzip",
            "anime_catalog.manifest.json",
            "anime_groups.jsonl.gzip",
            "anime_groups.manifest.json"
        )
        MediaType.BOOK -> listOf("book_catalog.jsonl.gzip", "book_catalog.manifest.json")
        else -> listOf(
            "${mediaType.persistedValue}_catalog.jsonl.gzip",
            "${mediaType.persistedValue}_catalog.manifest.json",
            "${mediaType.persistedValue}_groups.jsonl.gzip",
            "${mediaType.persistedValue}_groups.manifest.json"
        )
    }

    private fun registerTransfer(
        mediaType: MediaType,
        total: Long,
        installedTotal: Long
    ) = synchronized(transferLock) {
        if (transfers.isEmpty()) completedTransfers.clear()
        transfers[mediaType] = 0L to total
        installProgress[mediaType] = CategoryPackInstallProgress(
            CategoryPackInstallPhase.DOWNLOADING,
            0L,
            total,
            installedTotal
        )
        publishPackProgress(mediaType)
        publishProgress()
    }

    private fun startTransfer(mediaType: MediaType) = synchronized(transferLock) {
        activeTransfer = mediaType
        publishProgress()
    }

    private fun updateTransfer(mediaType: MediaType, downloaded: Long, total: Long) = synchronized(transferLock) {
        transfers[mediaType] = downloaded to total
        val installedTotal = installProgress[mediaType]?.totalInstalledBytes ?: total
        installProgress[mediaType] = CategoryPackInstallProgress(
            CategoryPackInstallPhase.DOWNLOADING,
            downloaded.coerceIn(0L, total),
            total,
            installedTotal
        )
        publishPackProgress(mediaType)
        publishProgress()
    }

    private fun updateInstallPhase(mediaType: MediaType, phase: CategoryPackInstallPhase) =
        synchronized(transferLock) {
            val (_, total) = transfers[mediaType] ?: (0L to 0L)
            val installedTotal = installProgress[mediaType]?.totalInstalledBytes ?: total
            installProgress[mediaType] = CategoryPackInstallProgress(
                phase,
                total,
                total,
                installedTotal
            )
            publishPackProgress(mediaType)
        }

    private fun clearInstallProgress(mediaType: MediaType) = synchronized(transferLock) {
        installProgress.remove(mediaType)
        publishPackProgress(mediaType)
    }

    private fun setRuntimeStatus(mediaType: MediaType, status: CategoryPackRuntimeStatus) =
        synchronized(transferLock) {
            runtimeStatuses[mediaType] = status
            val current = _states.value[mediaType]
            if (current != null) {
                _states.value = _states.value + (mediaType to current.copy(runtimeStatus = status))
            }
        }

    private suspend fun recoverPreviousRuntime(
        mediaType: MediaType,
        importInstalledPack: suspend ((CategoryPackInstallPhase) -> Unit) -> Boolean
    ) {
        if (installer.installedDirectory(mediaType) == null) {
            setRuntimeStatus(mediaType, CategoryPackRuntimeStatus.FILES_MISSING)
            return
        }
        val recovered = runCatching {
            importInstalledPack { phase -> updateInstallPhase(mediaType, phase) }
        }.getOrElse {
            Log.e(PACK_LOG_TAG, "pack=${mediaType.toPackId().value} rollback recovery failed", it)
            false
        }
        setRuntimeStatus(
            mediaType,
            if (recovered) CategoryPackRuntimeStatus.READY else CategoryPackRuntimeStatus.ERROR
        )
    }

    private fun lifecycleMutex(mediaType: MediaType): Mutex =
        lifecycleMutexes.getOrPut(mediaType.toPackId()) { Mutex() }

    private fun finishTransfer(mediaType: MediaType) = synchronized(transferLock) {
        val total = transfers[mediaType]?.second ?: 0L
        transfers[mediaType] = total to total
        completedTransfers += mediaType
        installProgress.remove(mediaType)
        if (activeTransfer == mediaType) activeTransfer = null
        if (completedTransfers.size == transfers.size) {
            transfers.clear()
            completedTransfers.clear()
        }
        publishProgress()
    }

    private fun publishPackProgress(mediaType: MediaType) {
        val current = _states.value[mediaType] ?: stateFor(mediaType)
        _states.value = _states.value +
            (mediaType to current.copy(installProgress = installProgress[mediaType]))
    }

    private fun publishProgress() {
        _downloadProgress.value = transfers.takeIf { it.isNotEmpty() }
            ?.let { current ->
                aggregateCategoryDownloadProgress(
                    transfers = current.values,
                    currentMediaType = activeTransfer,
                    currentPosition = activeTransfer?.let { completedTransfers.size + 1 } ?: 0,
                    transferCount = current.size
                )
            }
    }

    companion object {
        const val PACK_ROOT = "packs"
        const val LEGACY_PACK_ROOT = "catalog-packs"
        const val INDEX_ASSET = "category_pack_index.json"
        private const val PACK_LOG_TAG = "RankOfflinePack"
    }
}

private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

internal fun isCompatibleAppVersion(minimum: String, current: String): Boolean {
    fun components(value: String): List<Int> = Regex("\\d+").findAll(value)
        .take(3).map { it.value.toInt() }.toList().let { it + List(3 - it.size) { 0 } }
    val min = components(minimum)
    val actual = components(current)
    return (0..2).firstNotNullOfOrNull { index ->
        (actual[index] - min[index]).takeIf { it != 0 }
    }?.let { it > 0 } ?: true
}

internal fun loadCategoryPackIndex(context: Context): Map<PackId, CategoryPackDescriptor> {
    val root = JSONObject(context.assets.open(CategoryPackManager.INDEX_ASSET).bufferedReader().use { it.readText() })
    require(root.getInt("schemaVersion") == 1)
    val packs = root.getJSONArray("packs")
    return buildMap {
        repeat(packs.length()) { index ->
            val pack = packs.getJSONObject(index)
            val manifestJson = pack.getJSONObject("manifest")
            val manifest = PackManifestParser.parse(manifestJson)
            val packId = manifest.packId
            val encodedManifest = manifestJson.toString()
            val files = manifest.files.map { CategoryPackFileSpec(it.path, it.bytes, it.sha256) }
            val downloadBytes = pack.getLong("downloadBytes")
            put(
                packId,
                CategoryPackDescriptor(
                    installManifest = CategoryPackInstallManifest(
                        packId = packId,
                        snapshot = manifest.version,
                        downloadBytes = downloadBytes,
                        installedBytes = pack.getLong("installedBytes"),
                        files = files,
                        encodedManifest = encodedManifest
                    ),
                    manifest = manifest,
                    formatVersion = pack.getInt("formatVersion"),
                    minimumDatabaseVersion = pack.getInt("minimumDatabaseVersion"),
                    archiveName = pack.getString("archive"),
                    archiveBytes = downloadBytes,
                    archiveSha256 = pack.getString("archiveSha256")
                )
            )
        }
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun File.recursiveFileBytes(): Long = when {
    isFile -> length()
    isDirectory -> listFiles().orEmpty().sumOf(File::recursiveFileBytes)
    else -> 0L
}

private fun java.io.InputStream.countBytes(): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return total
        total += read
    }
}
