package org.rankoffline.app

import android.content.Context

/**
 * Process-wide dependency boundary shared by UI and persistent workers.
 *
 * Pack lifecycle locks must live here: constructing a manager in every Activity/Worker would let
 * two operations mutate the same pack concurrently.
 */
internal class RankOfflineServices private constructor(context: Context) {
    private val appContext = context.applicationContext
    val database = AnimeDatabase.getInstance(appContext)
    val settingsRepository = SettingsRepository(appContext)
    val packStorageRepository = PackStorageRepository(database, settingsRepository.state)
    val packOperationScheduler = PackOperationScheduler()
    val categoryPackManager = CategoryPackManager(
        appContext,
        operationScheduler = packOperationScheduler
    )
    val genericRepository = GenericMediaRepository(database) { mediaType ->
        categoryPackManager.descriptor(mediaType)?.manifest
    }
    val genericGroupRepository = GenericMediaGroupRepository(database)
    val bundledCatalogManager = BundledMediaCatalogManager(appContext, database)
    val genericCoverRepository = GenericMediaCoverRepository(
        appContext,
        database,
        manifestProvider = { mediaType -> categoryPackManager.descriptor(mediaType)?.manifest }
    )
    val genericGroupCoverRepository = GenericMediaGroupCoverRepository(
        appContext,
        database,
        manifestProvider = { mediaType -> categoryPackManager.descriptor(mediaType)?.manifest }
    )
    val coverPreloadManager = CoverPreloadManager(genericCoverRepository, packOperationScheduler)
    val personalDataBackupManager = PersonalDataBackupManager(
        appContext,
        database,
        settingsRepository,
        categoryPackManager
    )
    val rankingShareExporter = RankingShareExporter(appContext) { scope ->
        val mediaType = (scope as? ViewingScope.Pack)?.mediaType ?: return@RankingShareExporter null
        val manifest = categoryPackManager.descriptor(mediaType)?.manifest
            ?: return@RankingShareExporter null
        val directory = categoryPackManager.installedDirectory(mediaType)
            ?: return@RankingShareExporter null
        manifest.resolveShareBackground(directory)
    }
    val runtimeImporter = PackRuntimeImporter(
        categoryPackManager,
        bundledCatalogManager,
        genericRepository
    )
    val installCoordinator = PackInstallCoordinator(
        categoryPackManager,
        settingsRepository,
        runtimeImporter,
        coverPreloadManager
    )

    companion object {
        @Volatile private var instance: RankOfflineServices? = null

        fun get(context: Context): RankOfflineServices = instance ?: synchronized(this) {
            instance ?: RankOfflineServices(context).also { instance = it }
        }
    }
}

internal class PackRuntimeImporter(
    private val packManager: CategoryPackManager,
    private val catalogManager: BundledMediaCatalogManager,
    private val repository: GenericMediaRepository
) {
    suspend fun isCurrent(mediaType: MediaType): Boolean =
        packManager.installedArtifactIsCurrent(mediaType) &&
            catalogManager.isCurrent(mediaType, packManager.descriptor(mediaType)?.manifest)

    suspend fun import(
        mediaType: MediaType,
        onPhase: (CategoryPackInstallPhase) -> Unit = {}
    ): CatalogAvailability {
        val installedDirectory = packManager.installedDirectory(mediaType)
        val manifest = packManager.descriptor(mediaType)?.manifest
        val availability = catalogManager.importOptional(mediaType, manifest, onPhase)
        if (availability == CatalogAvailability.AVAILABLE) {
            repository.refreshSharedCoverIdentities(
                mediaType,
                manifest?.groupTypeOrNull() ?: mediaType.genericGroupType()
            )
            if (installedDirectory != null && manifest != null) {
                repository.installPackCovers(manifest, installedDirectory)
            }
        }
        return availability
    }
}

internal class PackInstallCoordinator(
    private val packManager: CategoryPackManager,
    private val settingsRepository: SettingsRepository,
    private val runtimeImporter: PackRuntimeImporter,
    private val coverPreloadManager: CoverPreloadManager
) {
    suspend fun restoreReadyIfCurrent(
        mediaType: MediaType,
        includeStorageDetails: Boolean = true
    ): Boolean {
        if (!runtimeImporter.isCurrent(mediaType)) return false
        return packManager.publishVerifiedReady(mediaType, includeStorageDetails)
    }
    suspend fun install(mediaType: MediaType): CategoryPackDownloadResult {
        settingsRepository.loadInitialState()
        if (packManager.installedArtifactIsCurrent(mediaType)) {
            val recovered = recover(mediaType)
            if (recovered == CatalogAvailability.AVAILABLE) {
                resumeInitialCoversIfNeeded(mediaType)
                return CategoryPackDownloadResult.INSTALLED
            }
        }
        val firstInstallation = packManager.installedDirectory(mediaType) == null
        val manifestDefaults = packManager.descriptor(mediaType)?.manifest?.defaults
        val mediaSettings = if (firstInstallation && manifestDefaults != null) {
            settingsRepository.initializePackSettings(mediaType, manifestDefaults)
        } else {
            settingsRepository.state.value.mediaSettings.getValue(mediaType)
        }
        return packManager.downloadAndInstall(
            mediaType = mediaType,
            importInstalledPack = { reportPhase ->
                runtimeImporter.import(mediaType, reportPhase) == CatalogAvailability.AVAILABLE
            },
            downloadInitialCovers = mediaSettings.showImages,
            acquireInitialCovers = { reportProgress ->
                coverPreloadManager.preloadTop(
                    mediaType = mediaType,
                    limit = CategoryPackManager.INITIAL_COVER_TARGET,
                    oneCoverPerGroup = mediaSettings.oneCoverPerGroup,
                    onProgress = reportProgress
                )
            }
        )
    }

    suspend fun resumeInitialCoversIfNeeded(mediaType: MediaType): Boolean {
        val status = packManager.initialCoverAcquisition(mediaType) ?: return true
        if (status.status != InitialCoverAcquisitionStatus.PENDING) return true
        val settings = settingsRepository.state.value.mediaSettings.getValue(mediaType)
        if (!settings.showImages) {
            packManager.skipPendingInitialCoverAcquisition(mediaType)
            return true
        }
        packManager.resumeInitialCoverAcquisition(mediaType) { reportProgress ->
            coverPreloadManager.preloadTop(
                mediaType,
                CategoryPackManager.INITIAL_COVER_TARGET,
                settings.oneCoverPerGroup,
                reportProgress
            )
        }
        return true
    }

    suspend fun recover(mediaType: MediaType): CatalogAvailability {
        if (restoreReadyIfCurrent(mediaType)) return CatalogAvailability.AVAILABLE
        return packManager.recoverRuntime(
            mediaType = mediaType,
            clearMissingCatalog = { /* startup owns missing-catalog reconciliation */ },
            initializeRuntime = { reportPhase -> runtimeImporter.import(mediaType, reportPhase) }
        )
    }
}
