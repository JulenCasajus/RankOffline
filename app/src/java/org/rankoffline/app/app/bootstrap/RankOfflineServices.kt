package org.rankoffline.app

import android.content.Context
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The application supplies the *trusted* source registry loader and HTTPS archive transport.
 * It must configure these once in Application.onCreate, including WorkManager-only launches.
 * There are deliberately no built-in Anime/Manga/... sources or guessed URLs here.
 */
internal class PackServiceConfiguration(
    val downloader: PackArchiveDownloader,
    val loadTrustedSources: suspend (PackSourceRegistry) -> Unit,
    val currentAppVersion: String = BuildConfig.VERSION_NAME
)

/**
 * Process-wide dependency graph. Property initialization is lazy so opening a simple UI screen
 * doesn't open Room, enumerate packs, create downloader clients or start background tasks.
 *
 * Workers and UI use the SAME PackManager. PackManager owns installation/import/recovery;
 * nothing here reimplements those operations.
 */
internal class RankOfflineServices private constructor(
    context: Context,
    private val configuration: PackServiceConfiguration
) : PackWorkerRuntime {
    private val appContext: Context = context.applicationContext
    private val sourceLoadLock = Mutex()
    private val localSummaries by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LocalPackSummaryStore(File(appContext.filesDir, "local"))
    }

    val database: RankOfflineDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RankOfflineDatabase.getInstance(appContext)
    }
    val settingsRepository: SettingsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SettingsRepository(appContext)
    }
    val ratingRepository: RatingRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RatingRepository(database)
    }
    val mediaRepository: MediaRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MediaRepository(database, ratingRepository)
    }
    val groupRepository: GroupRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GroupRepository(database, ratingRepository)
    }

    val packSources: PackSourceRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PackSourceRegistry()
    }
    val catalogImporter: CatalogImporter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CatalogImporter(database)
    }
    private val sharedPackScheduler by lazy { PackOperationScheduler() }
    override val packManager: PackManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PackManager(
            packRoot = File(appContext.filesDir, "packs"),
            cacheRoot = File(appContext.cacheDir, "pack-downloads"),
            sources = packSources,
            downloader = configuration.downloader,
            synchronizer = RoomPackCatalogSynchronizer(catalogImporter),
            catalogCleaner = RoomPackCatalogCleaner(database),
            currentAppVersion = configuration.currentAppVersion,
            metrics = RoomPackMetrics(database) { mediaCoverRepository },
            summaries = localSummaries,
            scheduler = sharedPackScheduler
        )
    }

    val mediaCoverRepository: CoverRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CoverRepository(
            context = appContext,
            database = database,
            manifestProvider = { id ->
                packManager.packDefinition(id)?.manifest ?: packManager.descriptor(id)?.manifest
            },
            beforeCoverMutation = { id, fields -> packManager.invalidateCoverSummary(id, fields) }
        )
    }
    val coverDownloadsManager: CoverDownloadsManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CoverDownloadsManager(mediaCoverRepository, sharedPackScheduler) { id, progress ->
            localSummaries.recordBatch(id, progress)
        }
    }

    /** On-demand only: logical Room values + physical per-pack editable JSON sizes. */
    val personalDataBackupManager: PersonalDataBackupManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PersonalDataBackupManager(appContext, database, settingsRepository, packManager)
    }
    val rankingShareExporter: RankingShareExporter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RankingShareExporter(appContext) { viewing, template ->
            val defaultName = when (template) {
                RankingShareTemplate.TOP_SEVEN -> "default_share_top7.webp"
                RankingShareTemplate.TOP_TEN -> "default_share_top10.webp"
            }
            val defaultBackground = File(appContext.filesDir, "share-defaults/$defaultName")
            val id = (viewing as? ViewingScope.Pack)?.packId
            val specific = id?.let {
                val manifest = packManager.packDefinition(it)?.manifest
                val directory = packManager.installedDirectory(it)
                if (manifest != null && directory != null) manifest.resolveShareBackground(
                    directory, if (template == RankingShareTemplate.TOP_SEVEN)
                        PackShareTemplate.TOP_SEVEN else PackShareTemplate.TOP_TEN)
                else null
            }
            specific?.takeIf { it.isFile } ?: defaultBackground.takeIf { it.isFile }
        }
    }

    val packStorageRepository: PackStorageRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val localBytes: Flow<Map<PackId, Long>> = settingsRepository.state.map {
            val root = File(appContext.filesDir, "local")
            root.listFiles().orEmpty().asSequence()
                .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
                .mapNotNull { directory ->
                    val id = runCatching { PackId.parse(directory.name) }.getOrNull()
                        ?: return@mapNotNull null
                    val preferences = File(directory, "preferences.json")
                    val rating = File(directory, "rating/rating.json")
                    val bytes = listOf(preferences, rating).sumOf { file ->
                        if (file.isFile && !Files.isSymbolicLink(file.toPath())) file.length() else 0L
                    }
                    id to bytes
                }.toMap()
        }.flowOn(Dispatchers.IO)
        PackStorageRepository(database, localBytes)
    }

    /**
     * Fast UI bootstrap: global DataStore once, discover installed packs, load ONLY active
     * pack's editable JSON and recover ONLY its catalog. Other packs load when selected.
     * The UI must use lastTab from settingsRepository.state, not construct every tab at start.
     */
    suspend fun initializeVisiblePack(): PackId? {
        settingsRepository.loadInitialState()
        packManager.refresh()
        settingsRepository.syncInstalledPacks(installedManifests())
        val active = settingsRepository.state.value.activePackId ?: return null
        // Virtual All uses its own coordinated recovery, not a separate eager pack read.
        if (settingsRepository.state.value.lastViewingScope == ViewingScope.All) return active
        if (packManager.states.value[active]?.status != PackRuntimeStatus.READY) {
            if (!packManager.recover(active)) return null
        }
        return active
    }

    /** Recover installed packs for virtual All without touching catalog pages or covers. */
    suspend fun ensureAllPacksReady() {
        settingsRepository.loadInitialState()
        packManager.refresh()
        val installed = packManager.states.value.values.filter { it.installed }.map { it.packId }
        // The Room importer already serializes writes. Bound recovery workers to avoid I/O bursts.
        val limiter = kotlinx.coroutines.sync.Semaphore(2)
        kotlinx.coroutines.coroutineScope {
            installed.map { id -> async {
                limiter.withPermit {
                    if (packManager.states.value[id]?.status != PackRuntimeStatus.READY) {
                        check(packManager.recover(id)) { "Failed to recover pack ${id.value} for All" }
                    }
                }
            } }.awaitAll()
        }
        settingsRepository.syncInstalledPacks(installedManifests())
        settingsRepository.ensurePackSettingsLoaded(installed)
    }

    /** Called when opening another pack. No eager full-catalog read or cover downloading. */
    suspend fun ensurePackReady(packId: PackId): Boolean {
        settingsRepository.loadInitialState()
        if (packManager.states.value.isEmpty()) packManager.refresh()
        val state = packManager.states.value[packId] ?: return false
        if (!state.installed) return false
        if (state.status != PackRuntimeStatus.READY && !packManager.recover(packId)) return false
        settingsRepository.syncInstalledPacks(installedManifests())
        settingsRepository.ensurePackSettingsLoaded(packId)
        return true
    }

    /** UI Storage screen only; no eager count or filesystem traversal during startup. */
    suspend fun refreshPackStatistics(
        packId: PackId,
        fields: Set<PackSummaryField> = PackSummaryField.entries.toSet()
    ): LocalPackSummary? = packManager.refreshSummary(packId, fields)

    /** Explicitly cancel optional initial cover Work. Cancellation is not remembered. */
    suspend fun cancelInitialCoverDownloads(packId: PackId) =
        PackWorkScheduler.cancelInitialCovers(appContext, packId)

    /** Fetch trusted network metadata on explicit discovery or on worker INSTALL, not every cold start. */
    suspend fun refreshAvailablePacks() {
        sourceLoadLock.withLock { configuration.loadTrustedSources(packSources) }
        packManager.refresh()
    }

    /** Worker entry point: no Activity or in-memory UI must have run first. */
    override suspend fun prepare(packId: PackId, action: PackWorkAction) {
        when (action) {
            PackWorkAction.INSTALL -> refreshAvailablePacks()
            PackWorkAction.RECOVER -> packManager.refresh()
            PackWorkAction.INITIAL_COVERS -> {
                packManager.refresh()
                // WorkManager may resume in a new process where READY was only in memory.
                val state = packManager.states.value[packId]
                if (state?.installed == true && state.status != PackRuntimeStatus.READY) {
                    check(packManager.recover(packId)) { "Cannot restore catalog before initial covers" }
                }
            }
        }
    }

    /** Initial 100 covers are a separate WorkManager stage, not a persisted status flag. */
    override suspend fun resumeInitialCoversIfNeeded(packId: PackId): Boolean {
        val state = packManager.states.value[packId] ?: return true
        if (!state.installed || state.status != PackRuntimeStatus.READY) return true
        loadPackSettingsForWorker(packId)
        if (settingsRepository.state.value.mediaSettings[packId]?.showImages != true) return true

        // The installer always uses the pack's original merge default, independently
        // of later per-user display or merge preferences.
        val defaultMerge = state.manifest?.defaults?.mergeEnabled == true
        val result = packManager.acquireInitialCovers(packId, INITIAL_COVER_COUNT) { update ->
            coverDownloadsManager.downloadTop(packId, INITIAL_COVER_COUNT,
                mergeEnabled = defaultMerge, useGroupCoversForItems = false) { value ->
                update(value.existing + value.downloaded, value.target)
            }
        }
        // Nonfatal image failures do NOT fail an otherwise valid installation or leave
        // a persisted PARTIAL/PENDING flag. User may request another download later.
        return result.status == CoverDownloadStatus.COMPLETED ||
            result.status == CoverDownloadStatus.FAILED
    }

    private suspend fun loadPackSettingsForWorker(packId: PackId) {
        settingsRepository.loadInitialState()
        settingsRepository.syncInstalledPacks(installedManifests())
        settingsRepository.ensurePackSettingsLoaded(packId)
    }

    private fun installedManifests(): List<PackManifest> =
        packManager.states.value.values.mapNotNull { state ->
            state.manifest?.takeIf { state.installed }
        }

    companion object {
        const val INITIAL_COVER_COUNT = 100

        @Volatile private var instance: RankOfflineServices? = null
        @Volatile private var setup: PackServiceConfiguration? = null

        /**
         * Call from Application.onCreate BEFORE WorkManager executes a worker. This registers
         * the lazy factory without constructing Room, DataStore, repositories or PackManager.
         */
        fun configure(configuration: PackServiceConfiguration) = synchronized(this) {
            check(instance == null) { "Configure PackServices before obtaining RankOfflineServices" }
            setup = configuration
            PackWorkerRuntimeProvider.configure { context -> get(context) }
        }

        fun get(context: Context): RankOfflineServices = instance ?: synchronized(this) {
            instance ?: RankOfflineServices(
                context.applicationContext,
                requireNotNull(setup) {
                    "Call RankOfflineServices.configure() from Application.onCreate"
                }
            ).also { instance = it }
        }
    }
}
