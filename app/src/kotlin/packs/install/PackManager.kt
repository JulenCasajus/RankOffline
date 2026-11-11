package org.rankoffline.app

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** The importer commits all catalog references (items and groups) in one Room transaction. */
fun interface PackCatalogSynchronizer {
    suspend fun sync(manifest: PackManifest, installedDirectory: File)
    suspend fun isCurrentFast(manifest: PackManifest, installedDirectory: File): Boolean = false
}

/** Removes catalog references only; personal ratings, notes and preferences stay. */
fun interface PackCatalogCleaner {
    suspend fun clear(packId: PackId)
}

class RoomPackCatalogSynchronizer(private val importer: CatalogImporter) : PackCatalogSynchronizer {
    override suspend fun sync(manifest: PackManifest, installedDirectory: File) {
        importer.importIfNeeded(manifest, installedDirectory)
    }
    override suspend fun isCurrentFast(manifest: PackManifest, installedDirectory: File): Boolean =
        importer.isCurrentFast(manifest, installedDirectory)
}

class RoomPackCatalogCleaner(private val database: RankOfflineDatabase) : PackCatalogCleaner {
    override suspend fun clear(packId: PackId) {
        database.mediaCatalogDao().deleteCatalogReferenceData(packId.value)
    }
}

/** First installation, recovery, deletion and optional cover acquisition; no pack updates. */
class PackManager internal constructor(
    internal val packRoot: File,
    internal val cacheRoot: File,
    internal val sources: PackSourceRegistry,
    internal val downloader: PackArchiveDownloader,
    internal val synchronizer: PackCatalogSynchronizer,
    internal val catalogCleaner: PackCatalogCleaner,
    internal val currentAppVersion: String,
    /** Optional to allow installation-only usage without opening Room for metrics. */
    internal val metrics: RoomPackMetrics? = null,
    internal val summaries: LocalPackSummaryStore = LocalPackSummaryStore(File(packRoot.parentFile, "local")),
    internal val scheduler: PackOperationScheduler = PackOperationScheduler()
) {
    internal val installer = PackInstaller(packRoot)
    internal val registry = PackRegistry(packRoot)
    internal val importMutex = Mutex()
    internal val coverCommitMonitor = Any()
    internal val pendingCoverCommits = MutableStateFlow(0)
    internal val refreshMutex = Mutex()
    internal val packLocks = ConcurrentHashMap<PackId, Mutex>()
    internal val runtime = ConcurrentHashMap<PackId, PackRuntimeStatus>()
    internal val stateLock = Any()
    internal val _states = MutableStateFlow<Map<PackId, PackState>>(emptyMap())
    internal val tracker = PackProgressTracker { id, progress ->
        synchronized(stateLock) {
            _states.value[id]?.let { _states.value = _states.value + (id to it.copy(progress = progress)) }
        }
    }

    val states: StateFlow<Map<PackId, PackState>> = _states.asStateFlow()
    val downloadProgress: StateFlow<PackAggregateProgress?> = tracker.aggregate
    val installProgress: StateFlow<Map<PackId, PackInstallProgress>> = tracker.progress

    internal fun packLock(id: PackId) = packLocks.computeIfAbsent(id) { Mutex() }

    internal companion object {
        val STAGING_NAME = Regex("^\\.(pack-[1-9][0-9]*)-" +
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
            "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.part$")
    }
}

/** A single on-demand filesystem traversal counts total bytes and covers, with no double counting. */
internal fun measurePackBytes(directory: File): Pair<Long, Long> {
    val root = directory.toPath().toAbsolutePath().normalize()
    check(java.nio.file.Files.isDirectory(root) && !java.nio.file.Files.isSymbolicLink(root))
    val covers = root.resolve("covers")
    var total = 0L
    var coversTotal = 0L
    java.nio.file.Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
        override fun visitFile(
            file: java.nio.file.Path,
            attributes: java.nio.file.attribute.BasicFileAttributes
        ): java.nio.file.FileVisitResult {
            if (!attributes.isSymbolicLink && attributes.isRegularFile) {
                total = Math.addExact(total, attributes.size())
                if (file.startsWith(covers)) coversTotal = Math.addExact(coversTotal, attributes.size())
            }
            return java.nio.file.FileVisitResult.CONTINUE
        }
    })
    return total to coversTotal
}
