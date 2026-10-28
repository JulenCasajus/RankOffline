package org.rankoffline.app

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.text.NumberFormat
import java.util.Locale

private const val RATING_LOG_TAG = "RankOfflineRating"

internal enum class StartupCatalogAction { PUBLISH_READY, RECOVER, WAIT_FOR_ACTIVE_OPERATION }

internal fun startupCatalogAction(
    catalogOperationActive: Boolean,
    verifiedCurrent: Boolean
): StartupCatalogAction = when {
    verifiedCurrent -> StartupCatalogAction.PUBLISH_READY
    catalogOperationActive -> StartupCatalogAction.WAIT_FOR_ACTIVE_OPERATION
    else -> StartupCatalogAction.RECOVER
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class RankOfflineViewModel(application: Application) : AndroidViewModel(application) {
    private val startupTiming = StartupTiming()
    private val services = RankOfflineServices.get(application)
    private val database = services.database
    // Stable 0.1.3 stored a small amount of cover metadata outside Room. This one-shot bridge is
    // deliberately isolated from the shared catalog/search/rating/cover runtime.
    private val legacyCoverMigration = LegacyAnimeCoverMetadataMigrator(application, database)
    private val categoryPackManager = services.categoryPackManager
    private val genericRepository = services.genericRepository
    private val genericGroupRepository = services.genericGroupRepository
    private val settingsRepository = services.settingsRepository
    val coverRepository = CoverBitmapCache()
    val genericCoverRepository = services.genericCoverRepository
    val genericGroupCoverRepository = services.genericGroupCoverRepository
    private val coverPreloadManager = services.coverPreloadManager

    val settings: StateFlow<AppSettingsState> = settingsRepository.state
    val categoryPackStates: StateFlow<Map<MediaType, CategoryPackState>> = categoryPackManager.states
    val categoryDownloadProgress: StateFlow<CategoryDownloadProgress?> = categoryPackManager.downloadProgress
    private val _viewingScope = MutableStateFlow<ViewingScope>(ViewingScope.Pack(MediaType.ANIME))
    val viewingScope: StateFlow<ViewingScope> = _viewingScope.asStateFlow()
    val allAvailable: StateFlow<Boolean> = categoryPackStates.map { states ->
        states.values.count(CategoryPackState::available) >= 2
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val packPersonalStorage: StateFlow<Map<MediaType, PackPersonalStorage>> = combine(
        MediaType.entries.map { mediaType ->
            services.packStorageRepository.observePersonalStorage(mediaType)
                .map { mediaType to it }
        }
    ) { entries -> entries.toMap() }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            MediaType.entries.associateWith { PackPersonalStorage() }
        )
    private val _messages = Channel<UserMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val searchStates = MutableStateFlow(defaultSearchUiStates())
    private val _filterOptions = MutableStateFlow(AnimeFilterOptions())
    val filterOptions: StateFlow<AnimeFilterOptions> = _filterOptions.asStateFlow()
    private val _genericFilterOptions = MutableStateFlow(
        MediaType.entries.associateWith { GenericMediaFilterOptions() }
    )
    val genericFilterOptions: StateFlow<Map<MediaType, GenericMediaFilterOptions>> =
        _genericFilterOptions.asStateFlow()
    private val _catalogAvailability = MutableStateFlow(
        MediaType.entries.associateWith { CatalogAvailability.LOADING }
    )
    val catalogAvailability: StateFlow<Map<MediaType, CatalogAvailability>> =
        _catalogAvailability.asStateFlow()
    private val revisions = MediaRevisionTracker()
    private val ratingDisplayOverrides = MutableStateFlow<Map<String, RatingDisplayOverride>>(emptyMap())
    private val coverOperations = CoverOperationsCoordinator(
        viewModelScope,
        settings,
        genericCoverRepository,
        genericGroupCoverRepository,
        coverPreloadManager,
        _messages::send,
        onStorageChanged = { categoryPackManager.refresh() }
    )
    val coverPreloadProgress: StateFlow<CoverPreloadProgress> = combine(
        coverOperations.progressByMedia,
        settings
    ) { progressByMedia, currentSettings ->
        progressByMedia[currentSettings.activeMediaType]
            ?: CoverPreloadProgress(mediaType = currentSettings.activeMediaType)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CoverPreloadProgress())
    private val rankingStates = MutableStateFlow(defaultRankingUiStates())
    private val activeSearchSnapshot: StateFlow<ActiveSearchSnapshot> = combine(searchStates, settings) { states, currentSettings ->
        ActiveSearchSnapshot(
            mediaType = currentSettings.activeMediaType,
            state = states.searchStateFor(currentSettings.activeMediaType),
            merge = currentSettings.mergeEnabled && currentSettings.activeMediaType.supportsMerge()
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ActiveSearchSnapshot(MediaType.ANIME, SearchUiState(), merge = false)
    )
    private val packActiveSearchState: StateFlow<SearchUiState> = activeSearchSnapshot
        .map { it.state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())
    private val activeRankingSnapshot: StateFlow<ActiveRankingSnapshot> = combine(rankingStates, settings) { states, currentSettings ->
        val validCategories = currentSettings.ratingConfig.categories
            .filter(RatingCategory::active)
            .mapTo(mutableSetOf()) { it.id }
        ActiveRankingSnapshot(
            mediaType = currentSettings.activeMediaType,
            state = states.rankingStateFor(currentSettings.activeMediaType).withValidCategory(validCategories),
            merge = currentSettings.mergeEnabled && currentSettings.activeMediaType.supportsMerge()
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ActiveRankingSnapshot(MediaType.ANIME, RankingUiState(), merge = false)
    )
    private val packActiveRankingState: StateFlow<RankingUiState> = activeRankingSnapshot
        .map { it.state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RankingUiState())
    private val allSearchState = MutableStateFlow(SearchUiState(sortOrder = CatalogSortOrder.POPULARITY))
    private val allRankingState = MutableStateFlow(RankingUiState())
    val activeSearchState: StateFlow<SearchUiState> = combine(
        viewingScope,
        packActiveSearchState,
        allSearchState
    ) { scope, pack, all -> if (scope == ViewingScope.All) all else pack }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())
    val activeRankingState: StateFlow<RankingUiState> = combine(
        viewingScope,
        packActiveRankingState,
        allRankingState,
        categoryPackStates,
        settings
    ) { scope, pack, all, packStates, currentSettings ->
        if (scope == ViewingScope.All) {
            all.withValidCategory(
                compatibleAllRatingCategories(
                    readyMediaTypes(packStates),
                    currentSettings.mediaSettings
                ).mapTo(hashSetOf(), RatingCategory::id)
            )
        } else pack
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RankingUiState())
    private val searchPageRequests = MutableSharedFlow<MediaType>(extraBufferCapacity = 1)
    private val allSearchPageRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val searchGeneration = AtomicLong(0L)
    private var lastSuccessfulSearchPage = SearchPageState()
    private val allSearchGeneration = AtomicLong(0L)
    private var lastSuccessfulAllSearchPage = SearchPageState(mediaType = null)
    private val rankingGeneration = AtomicLong(0L)
    private var lastSuccessfulRanking = RankingResultState()
    private val allRankingGeneration = AtomicLong(0L)
    private var lastSuccessfulAllRanking = RankingResultState(mediaType = null)
    private val _groupDetail = MutableStateFlow<GroupDetailUi?>(null)
    val groupDetail: StateFlow<GroupDetailUi?> = _groupDetail.asStateFlow()

    private val debouncedSearchQuery = activeSearchSnapshot
        .map { it.mediaType to it.state.query }
        .distinctUntilChanged()
        .debounce(300)

    private val searchRequest = combine(
        debouncedSearchQuery,
        revisions.catalog,
        revisions.ratings,
        activeSearchSnapshot
    ) { (queryMediaType, currentQuery), catalogRevisions, ratingRevisions, active ->
        if (queryMediaType != active.mediaType || currentQuery != active.state.query) {
            null
        } else {
            CatalogRequest(
                active.mediaType,
                currentQuery,
                active.state.sortOrder,
                active.state.filters,
                active.state.genericFilters,
                active.merge,
                catalogRevisions.getValue(active.mediaType),
                ratingRevisions.getValue(active.mediaType)
            )
        }
    }.filterNotNull().distinctUntilChanged()

    private val packSearchPageState: StateFlow<SearchPageState> = searchRequest
        .flatMapLatest { request ->
            flow {
                val generation = searchGeneration.incrementAndGet()
                val retained = retainedSearchPageState(
                    lastSuccessfulSearchPage,
                    request.mediaType,
                    request.query
                )
                emit(retained)
                var accumulator = SearchPageAccumulator<Anime>()
                try {
                    var page = loadSearchPage(request, INITIAL_SEARCH_PAGE_SIZE, accumulator.nextOffset)
                    if (searchGeneration.get() != generation) return@flow
                    accumulator = accumulator.append(page, INITIAL_SEARCH_PAGE_SIZE, Anime::id)
                    val firstPage = SearchPageState(
                        mediaType = request.mediaType,
                        query = request.query,
                        items = accumulator.items,
                        loadingInitial = false,
                        hasMore = accumulator.hasMore
                    )
                    lastSuccessfulSearchPage = firstPage
                    emit(firstPage)
                    searchPageRequests.filter { it == request.mediaType }.collect {
                        if (accumulator.hasMore) {
                            emit(
                                SearchPageState(
                                    mediaType = request.mediaType,
                                    query = request.query,
                                    items = accumulator.items,
                                    loadingInitial = false,
                                    loadingMore = true,
                                    hasMore = true
                                )
                            )
                            page = loadSearchPage(request, NEXT_SEARCH_PAGE_SIZE, accumulator.nextOffset)
                            if (searchGeneration.get() != generation) return@collect
                            accumulator = accumulator.append(page, NEXT_SEARCH_PAGE_SIZE, Anime::id)
                            val nextPage = SearchPageState(
                                mediaType = request.mediaType,
                                query = request.query,
                                items = accumulator.items,
                                loadingInitial = false,
                                hasMore = accumulator.hasMore
                            )
                            lastSuccessfulSearchPage = nextPage
                            emit(nextPage)
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Log.e(
                        "RankOfflineCatalog",
                        "operation=search media=${request.mediaType.persistedValue} failed=${exception.javaClass.simpleName}: ${exception.message}",
                        exception
                    )
                    if (searchGeneration.get() == generation) emit(
                        retained.copy(loadingInitial = false, loadingMore = false, failed = true)
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchPageState())

    private val packSearchResults: StateFlow<List<Anime>> = combine(
        packSearchPageState,
        ratingDisplayOverrides,
        activeSearchSnapshot
    ) { page, overrides, active ->
        page.visibleItemsFor(active.mediaType, overrides, active.state.query)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val packRankingResultState: StateFlow<RankingResultState> = combine(
        activeRankingSnapshot,
        revisions.catalog,
        revisions.ratings
    ) { active, catalog, ratings ->
            // Revisions are selected per media so an inactive category cannot restart this query.
            RankingRequest(
                active.mediaType,
                active.state.filters,
                active.state.genericFilters,
                active.state.sortOrder,
                active.state.descending,
                active.state.categoryId,
                active.merge,
                catalog.getValue(active.mediaType),
                ratings.getValue(active.mediaType)
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { request ->
            flow {
                val generation = rankingGeneration.incrementAndGet()
                val retained = retainedRankingResultState(lastSuccessfulRanking, request.mediaType)
                emit(retained)
                try {
                    val result = RankingResultState(
                        mediaType = request.mediaType,
                        items = genericRepository.ranking(
                            request.mediaType,
                            request.filtersForMedia(),
                            request.sort,
                            request.categoryId,
                            request.descending,
                            request.merge
                        ).map(MediaListEntry::toAnimeDisplay),
                        loading = false
                    )
                    if (rankingGeneration.get() != generation) return@flow
                    lastSuccessfulRanking = result
                    emit(result)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Log.e(
                        "RankOfflineCatalog",
                        "operation=ranking media=${request.mediaType.persistedValue} failed=${exception.javaClass.simpleName}: ${exception.message}",
                        exception
                    )
                    if (rankingGeneration.get() == generation) {
                        emit(retained.copy(loading = false, failed = true))
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankingResultState())

    private val packRanking: StateFlow<List<Anime>> = combine(packRankingResultState, settings) { result, currentSettings ->
        result.visibleItemsFor(currentSettings.activeMediaType)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allCatalogRequest = combine(
        allSearchState.debounce(300),
        categoryPackStates,
        settings,
        revisions.catalog,
        revisions.ratings
    ) { state, packStates, currentSettings, catalogRevisions, ratingRevisions ->
        val mediaTypes = readyMediaTypes(packStates)
        AllCatalogRequest(
            mediaTypes = mediaTypes,
            state = state,
            mergeByMedia = mediaTypes.associateWith { mediaType ->
                currentSettings.mediaSettings.getValue(mediaType).mergeEnabled && mediaType.supportsMerge()
            },
            revisionToken = mediaTypes.sumOf {
                catalogRevisions.getValue(it) * 31L + ratingRevisions.getValue(it)
            }
        )
    }.distinctUntilChanged()

    private val allSearchPageState: StateFlow<SearchPageState> = allCatalogRequest
        .flatMapLatest { request ->
            flow {
                val generation = allSearchGeneration.incrementAndGet()
                val retained = retainedAllSearchPageState(
                    lastSuccessfulAllSearchPage,
                    request.state.query,
                    request.mediaTypes.toSet()
                )
                emit(retained)
                try {
                    var cursor = AllCatalogCursor()
                    var page = loadAllSearchPage(request, INITIAL_SEARCH_PAGE_SIZE, cursor)
                    if (allSearchGeneration.get() != generation) return@flow
                    cursor = page.nextCursor
                    var items = page.entries.map(MediaListEntry::toAnimeDisplay)
                    var result = SearchPageState(
                        mediaType = null,
                        query = request.state.query,
                        items = items,
                        loadingInitial = false,
                        hasMore = page.hasMore
                    )
                    lastSuccessfulAllSearchPage = result
                    emit(result)
                    allSearchPageRequests.collect {
                        if (result.hasMore) {
                            emit(result.copy(loadingMore = true))
                            page = loadAllSearchPage(request, NEXT_SEARCH_PAGE_SIZE, cursor)
                            if (allSearchGeneration.get() != generation) return@collect
                            cursor = page.nextCursor
                            items = (items + page.entries.map(MediaListEntry::toAnimeDisplay))
                                .distinctBy { "${it.mediaType.persistedValue}:${it.id}" }
                            result = result.copy(
                                items = items,
                                loadingMore = false,
                                hasMore = page.hasMore
                            )
                            lastSuccessfulAllSearchPage = result
                            emit(result)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Log.e("RankOfflineCatalog", "operation=all-search failed", exception)
                    if (allSearchGeneration.get() == generation) {
                        emit(retained.copy(loadingInitial = false, loadingMore = false, failed = true))
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchPageState())

    private val allRankingRequest = combine(
        allRankingState,
        categoryPackStates,
        settings,
        revisions.catalog,
        revisions.ratings
    ) { state, packStates, currentSettings, catalogRevisions, ratingRevisions ->
        val mediaTypes = readyMediaTypes(packStates)
        val compatibleCategoryIds = compatibleAllRatingCategories(
            mediaTypes,
            currentSettings.mediaSettings
        ).mapTo(hashSetOf(), RatingCategory::id)
        AllRankingRequest(
            mediaTypes = mediaTypes,
            state = state.withValidCategory(compatibleCategoryIds),
            mergeByMedia = mediaTypes.associateWith { mediaType ->
                currentSettings.mediaSettings.getValue(mediaType).mergeEnabled && mediaType.supportsMerge()
            },
            revisionToken = mediaTypes.sumOf {
                catalogRevisions.getValue(it) * 31L + ratingRevisions.getValue(it)
            }
        )
    }.distinctUntilChanged()

    private val allRankingResultState: StateFlow<RankingResultState> = allRankingRequest
        .flatMapLatest { request ->
            flow {
                val generation = allRankingGeneration.incrementAndGet()
                val retained = if (!lastSuccessfulAllRanking.loading && !lastSuccessfulAllRanking.failed) {
                    lastSuccessfulAllRanking.copy(
                        items = lastSuccessfulAllRanking.items.filter {
                            it.mediaType in request.mediaTypes
                        },
                        failed = false
                    )
                } else RankingResultState(mediaType = null, loading = true)
                emit(retained)
                try {
                    val result = RankingResultState(
                        mediaType = null,
                        items = genericRepository.rankingAll(
                            request.mediaTypes,
                            request.state.genericFilters,
                            request.state.sortOrder,
                            request.state.categoryId,
                            request.state.descending,
                            request.mergeByMedia
                        ).map(MediaListEntry::toAnimeDisplay),
                        loading = false
                    )
                    if (allRankingGeneration.get() != generation) return@flow
                    lastSuccessfulAllRanking = result
                    emit(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Log.e("RankOfflineCatalog", "operation=all-ranking failed", exception)
                    if (allRankingGeneration.get() == generation) emit(
                        retained.copy(loading = false, failed = true)
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankingResultState())

    val searchPageState: StateFlow<SearchPageState> = combine(
        viewingScope,
        packSearchPageState,
        allSearchPageState
    ) { scope, pack, all -> if (scope == ViewingScope.All) all else pack }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchPageState())

    val searchResults: StateFlow<List<Anime>> = combine(
        viewingScope,
        packSearchResults,
        allSearchPageState,
        ratingDisplayOverrides
    ) { scope, pack, all, overrides ->
        if (scope != ViewingScope.All) pack else if (all.loadingInitial) emptyList()
        else applyRatingOverrides(all.items, overrides)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rankingResultState: StateFlow<RankingResultState> = combine(
        viewingScope,
        packRankingResultState,
        allRankingResultState
    ) { scope, pack, all -> if (scope == ViewingScope.All) all else pack }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankingResultState())

    val ranking: StateFlow<List<Anime>> = combine(
        viewingScope,
        packRanking,
        allRankingResultState
    ) { scope, pack, all ->
        if (scope != ViewingScope.All) pack else if (all.loading) emptyList() else all.items
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloadedCoverCount: StateFlow<Int> = settings
        .map { it.activeMediaType }
        .distinctUntilChanged()
        .flatMapLatest { mediaType ->
            genericCoverRepository.observeDownloadedCoverCount(mediaType)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _catalogCount = MutableStateFlow(0)
    val catalogCount: StateFlow<Int> = _catalogCount.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _errorMessage = MutableStateFlow<UserMessage?>(null)
    val errorMessage: StateFlow<UserMessage?> = _errorMessage.asStateFlow()
    private val _backupPreview = MutableStateFlow<PersonalBackupPreview?>(null)
    val backupPreview: StateFlow<PersonalBackupPreview?> = _backupPreview.asStateFlow()
    private val personalDataTransferCoordinator = PersonalDataTransferCoordinator()
    val personalDataTransfer: StateFlow<PersonalDataTransferState> =
        personalDataTransferCoordinator.state
    private val _pendingPersonalDataShare = MutableStateFlow<Uri?>(null)
    val pendingPersonalDataShare: StateFlow<Uri?> = _pendingPersonalDataShare.asStateFlow()
    private val _rankingShareData = MutableStateFlow(
        RankingShareData(ViewingScope.Pack(MediaType.ANIME))
    )
    internal val rankingShareData: StateFlow<RankingShareData> = _rankingShareData.asStateFlow()
    private val _rankingShareExport = MutableStateFlow(RankingShareExportState())
    internal val rankingShareExport: StateFlow<RankingShareExportState> = _rankingShareExport.asStateFlow()
    private val _pendingRankingShare = MutableStateFlow<Uri?>(null)
    val pendingRankingShare: StateFlow<Uri?> = _pendingRankingShare.asStateFlow()
    private var rankingShareLoadJob: Job? = null

    private val _ratingEditor = MutableStateFlow<RatingEditorState?>(null)
    val ratingEditor: StateFlow<RatingEditorState?> = _ratingEditor.asStateFlow()

    init {
        startupTiming.mark("ViewModel created")
        viewModelScope.launch {
            try {
                settingsRepository.loadInitialState()
                if (services.personalDataBackupManager.hasPendingRecovery()) {
                    services.personalDataBackupManager.recoverInterruptedImport()
                }
                _viewingScope.value = ViewingScope.Pack(settings.value.activeMediaType)
                searchStates.value = searchStates.value.withCatalogSortOrders(
                    settings.value.catalogSortOrders
                )
                allSearchState.value = allSearchState.value.copy(
                    sortOrder = settings.value.allViewSettings.catalogSortOrder
                )
                startupTiming.mark("DataStore loaded")
                settingsRepository.takeRemovedLegacyCategoryIds().forEach { categoryId ->
                    MediaType.entries.forEach { mediaType ->
                        genericRepository.deleteRatingCategory(mediaType, categoryId)
                        genericGroupRepository.deleteGroupRatingCategory(mediaType, categoryId)
                    }
                }
                categoryPackManager.recoverFilesystem()
                categoryPackManager.refresh(includeStorageDetails = false)
                PackBackgroundWorker.reconcilePersistentWork(
                    getApplication(),
                    categoryPackManager
                )
                startupTiming.mark("category packs refreshed")
                MediaType.entries.forEach { mediaType ->
                    if (categoryPackManager.installedDirectory(mediaType) != null) {
                        val activeProgress = categoryPackManager.states.value[mediaType]
                            ?.installProgress
                        val catalogOperationActive = activeProgress != null &&
                            activeProgress.phase != CategoryPackInstallPhase.WAITING_COVERS &&
                            activeProgress.phase != CategoryPackInstallPhase.DOWNLOADING_COVERS
                        val canUseFastVerification = !catalogOperationActive ||
                            activeProgress?.phase == CategoryPackInstallPhase.WAITING_IMPORT
                        val verifiedCurrent = canUseFastVerification &&
                            services.installCoordinator.restoreReadyIfCurrent(
                                mediaType,
                                includeStorageDetails = false
                            )
                        val action = startupCatalogAction(catalogOperationActive, verifiedCurrent)
                        val restored = action == StartupCatalogAction.PUBLISH_READY
                        _catalogAvailability.value = _catalogAvailability.value + (
                            mediaType to if (restored) CatalogAvailability.AVAILABLE
                            else CatalogAvailability.LOADING
                        )
                        if (action == StartupCatalogAction.RECOVER) {
                            PackBackgroundWorker.enqueue(
                                getApplication(),
                                mediaType,
                                PackWorkAction.RECOVER
                            )
                        } else if (restored &&
                            categoryPackManager.initialCoverAcquisition(mediaType)?.status ==
                            InitialCoverAcquisitionStatus.PENDING
                        ) {
                            PackBackgroundWorker.enqueue(
                                getApplication(),
                                mediaType,
                                PackWorkAction.INITIAL_COVERS
                            )
                        }
                    } else {
                        deleteCatalogReferenceData(mediaType)
                        _catalogAvailability.value = _catalogAvailability.value +
                            (mediaType to CatalogAvailability.UNAVAILABLE)
                    }
                }
                selectAvailableMediaIfNeeded()
                _ready.value = true
                startupTiming.mark("verified catalog state restored")
                categoryPackManager.refresh()
                startupTiming.mark("pack storage measured")
                legacyCoverMigration.migrate()
                genericRepository.reconcileLegacyAnimeCoverMetadata()
                startupTiming.mark("legacy cover metadata reconciled")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(
                    "RankOfflinePack",
                    "operation=startup-initialization failed=${exception.javaClass.simpleName}: ${exception.message}",
                    exception
                )
                _errorMessage.value = UserMessage.Text(R.string.error_initialize_local_data)
            } finally {
                _ready.value = true
            }
        }
        viewModelScope.launch {
            var previous = emptyMap<MediaType, CatalogAvailability>()
            categoryPackStates.collect { states ->
                val authoritative = MediaType.entries.associateWith { mediaType ->
                    when (states[mediaType]?.runtimeStatus) {
                        CategoryPackRuntimeStatus.READY -> CatalogAvailability.AVAILABLE
                        CategoryPackRuntimeStatus.FILES_MISSING, null -> CatalogAvailability.UNAVAILABLE
                        CategoryPackRuntimeStatus.ERROR -> CatalogAvailability.ERROR
                        CategoryPackRuntimeStatus.FILES_INSTALLED,
                        CategoryPackRuntimeStatus.INITIALIZING,
                        CategoryPackRuntimeStatus.PURGING -> CatalogAvailability.LOADING
                    }
                }
                _catalogAvailability.value = authoritative
                authoritative.forEach { (mediaType, availability) ->
                    if (availability == CatalogAvailability.AVAILABLE &&
                        previous[mediaType] != CatalogAvailability.AVAILABLE
                    ) {
                        refreshFilterOptions(mediaType)
                        invalidatePackRuntimeState(mediaType)
                    }
                }
                if (authoritative != previous) selectAvailableMediaIfNeeded()
                previous = authoritative
            }
        }
        viewModelScope.launch {
            allAvailable.collect { available ->
                if (!available && _viewingScope.value == ViewingScope.All) {
                    _viewingScope.value = ViewingScope.Pack(settings.value.activeMediaType)
                }
            }
        }
    }

    fun setSearchQuery(value: String) {
        updateActiveSearchState { it.copy(query = value) }
    }

    fun requestNextSearchPage() {
        if (viewingScope.value == ViewingScope.All) allSearchPageRequests.tryEmit(Unit)
        else searchPageRequests.tryEmit(settings.value.activeMediaType)
    }

    private suspend fun loadSearchPage(
        request: CatalogRequest,
        limit: Int,
        offset: Int
    ): List<Anime> = genericRepository.search(
            request.mediaType,
            request.query,
            request.sort,
            request.filtersForMedia(),
            request.merge,
            limit = limit,
            offset = offset
        ).map(MediaListEntry::toAnimeDisplay)

    private suspend fun loadAllSearchPage(
        request: AllCatalogRequest,
        limit: Int,
        cursor: AllCatalogCursor
    ): AllCatalogPage = genericRepository.searchAllPage(
        mediaTypes = request.mediaTypes,
        query = request.state.query,
        sortOrder = request.state.sortOrder,
        filters = request.state.genericFilters,
        mergeByMedia = request.mergeByMedia,
        limit = limit,
        cursor = cursor
    )

    fun setCatalogSortOrder(value: CatalogSortOrder) {
        updateActiveCatalogSortOrder { value }
    }

    fun setCatalogSortCriterion(value: CatalogSortOrder) {
        updateActiveCatalogSortOrder { current -> value.withReversed(current.isReversed) }
    }

    fun toggleCatalogSortDirection() {
        updateActiveCatalogSortOrder(CatalogSortOrder::inverted)
    }

    fun setCatalogFilters(value: AnimeFilters) {
        updateActiveSearchState { it.copy(filters = value) }
    }

    fun setGenericCatalogFilters(value: GenericMediaFilters) {
        updateActiveSearchState { it.copy(genericFilters = value) }
    }

    fun setRankingFilters(value: AnimeFilters) {
        updateActiveRankingState { it.copy(filters = value) }
    }

    fun setGenericRankingFilters(value: GenericMediaFilters) {
        updateActiveRankingState { it.copy(genericFilters = value) }
    }

    fun setRankingSortOrder(value: RankingSortOrder) {
        updateActiveRankingState {
            it.copy(
                sortOrder = value,
                categoryId = it.categoryId.takeIf { value == RankingSortOrder.CATEGORY_SCORE }
            )
        }
    }

    fun toggleRankingSortDirection() {
        updateActiveRankingState { it.copy(descending = !it.descending) }
    }

    fun setRankingCategory(categoryId: String) {
        updateActiveRankingState {
            it.copy(categoryId = categoryId, sortOrder = RankingSortOrder.CATEGORY_SCORE)
        }
    }

    private fun updateActiveSearchState(transform: (SearchUiState) -> SearchUiState) {
        if (viewingScope.value == ViewingScope.All) {
            allSearchState.value = transform(allSearchState.value)
            return
        }
        val mediaType = settings.value.activeMediaType
        searchStates.value = searchStates.value.updateSearchUiState(mediaType, transform)
    }

    private fun updateActiveCatalogSortOrder(
        transform: (CatalogSortOrder) -> CatalogSortOrder
    ) {
        if (viewingScope.value == ViewingScope.All) {
            val current = allSearchState.value.sortOrder
            val updated = transform(current)
            if (updated != current) allSearchState.value = allSearchState.value.copy(sortOrder = updated)
            if (updated != current) viewModelScope.launch {
                settingsRepository.updateAllCatalogSortOrder(updated)
            }
            return
        }
        val mediaType = settings.value.activeMediaType
        val current = searchStates.value.searchStateFor(mediaType).sortOrder
        val updated = transform(current)
        if (updated == current) return
        searchStates.value = searchStates.value.updateSearchUiState(mediaType) {
            it.copy(sortOrder = updated)
        }
        viewModelScope.launch {
            settingsRepository.updateCatalogSortOrder(mediaType, updated)
        }
    }

    private fun updateActiveRankingState(transform: (RankingUiState) -> RankingUiState) {
        if (viewingScope.value == ViewingScope.All) {
            allRankingState.value = transform(allRankingState.value)
            return
        }
        val mediaType = settings.value.activeMediaType
        rankingStates.value = rankingStates.value.updateRankingUiState(mediaType, transform)
    }

    fun openRating(anime: Anime) {
        _ratingEditor.value = RatingEditorState(anime)
        viewModelScope.launch {
            try {
                val draft = loadRatingDraft(RatingEditorState(anime), anime.id)
                if (_ratingEditor.value.matches(anime, isGroup = false)) {
                    _ratingEditor.value = RatingEditorState(anime, draft = draft)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                reportRatingFailure("load", RatingEditorState(anime), exception)
            }
        }
    }

    fun openAnime(anime: Anime) {
        if (anime.groupId == null) {
            openRating(anime)
        } else {
            viewModelScope.launch {
                val detail = genericGroupRepository.groupDetail(anime.groupId)?.toUi()
                detail?.let { openGroupRating(it, fromMembers = false) }
            }
        }
    }

    fun openGroupDetails(anime: Anime) {
        val groupId = anime.groupId ?: return
        viewModelScope.launch {
            _groupDetail.value = genericGroupRepository.groupDetail(groupId)?.toUi()
        }
    }

    fun closeGroup() { _groupDetail.value = null }

    fun rateGroup(fromMembers: Boolean) {
        val detail = _groupDetail.value ?: return
        openGroupRating(detail, fromMembers)
    }

    private fun openGroupRating(detail: GroupDetailUi, fromMembers: Boolean) {
        val root = detail.members.firstOrNull { it.id == detail.representativeMediaId }
            ?: detail.members.firstOrNull()
            ?: return
        val target = root.copy(
            id = detail.id, title = detail.displayTitle,
            mediaType = detail.mediaType,
            groupId = detail.id, groupType = detail.type,
            rootAnimeId = root.id,
            memberCount = detail.members.size,
            finalScore = detail.explicitFinalScore
        )
        _ratingEditor.value = RatingEditorState(target, isGroup = true)
        viewModelScope.launch {
            try {
                val draft = genericGroupRepository.loadGroupRating(detail.id, fromMembers)
                if (_ratingEditor.value.matches(target, isGroup = true)) {
                    _ratingEditor.value = RatingEditorState(target, isGroup = true, draft = draft)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                reportRatingFailure("load-group", RatingEditorState(target, isGroup = true), exception)
            }
        }
    }

    fun closeRating() {
        _ratingEditor.value = null
    }

    fun saveElaborateRating(
        animeId: String,
        draft: RatingDraft,
        finalScoreOverride: Double?,
        profileId: String
    ) {
        val target = _ratingEditor.value ?: return
        val sourceSettings = settings.value.mediaSettings.getValue(target.anime.mediaType)
        val profile = sourceSettings.ratingProfiles.resolveProfile(profileId)
        val config = sourceSettings.ratingConfig.withProfile(profile)
        val calculated = ScoreCalculator.calculate(draft.categoryScores, draft.additiveScore, config)
        launchRatingMutation(
            target = target,
            ratingId = animeId,
            operation = "save-elaborate",
            expectedDraft = draft.copy(profileId = profile.id),
            expectedDisplay = RatingDisplayOverride(
                finalScore = finalScoreOverride ?: calculated.finalScore,
                qualityScore = calculated.qualityScore,
                additiveScore = draft.additiveScore
            ),
            compareCategories = true
        ) {
            when {
                target.isGroup -> genericGroupRepository.saveElaborateGroupRating(
                    animeId, draft, config, finalScoreOverride, profile.id
                )
                else -> genericRepository.saveElaborateRating(
                    animeId, draft, config, finalScoreOverride, profile.id
                )
            }
        }
    }

    fun saveSimpleRating(animeId: String, draft: RatingDraft, qualityScore: Double, finalScore: Double) {
        val target = _ratingEditor.value ?: return
        launchRatingMutation(
            target = target,
            ratingId = animeId,
            operation = "save-simple",
            expectedDraft = draft,
            expectedDisplay = RatingDisplayOverride(finalScore, qualityScore, draft.additiveScore)
        ) {
            when {
                target.isGroup -> genericGroupRepository.saveSimpleGroupRating(
                    animeId, draft, qualityScore, finalScore
                )
                else -> genericRepository.saveSimpleRating(animeId, draft, qualityScore, finalScore)
            }
        }
    }

    fun deleteRating(animeId: String) {
        val target = _ratingEditor.value ?: return
        launchRatingMutation(
            target = target,
            ratingId = animeId,
            operation = "delete",
            expectDeleted = true
        ) {
            when {
                target.isGroup -> genericGroupRepository.deleteGroupRating(animeId)
                else -> genericRepository.deleteRating(target.anime.mediaType, animeId)
            }
        }
    }

    private fun launchRatingMutation(
        target: RatingEditorState,
        ratingId: String,
        operation: String,
        expectedDraft: RatingDraft? = null,
        expectedDisplay: RatingDisplayOverride? = null,
        compareCategories: Boolean = false,
        expectDeleted: Boolean = false,
        mutation: suspend () -> Unit
    ) {
        viewModelScope.launch {
            try {
                check(ratingId == target.anime.id) { "Rating identity changed while editor was open" }
                mutation()
                val persisted = loadRatingDraft(target, ratingId)
                if (expectDeleted) {
                    check(persisted.isEmptyPersistedRating()) { "Deleted rating is still present" }
                } else {
                    checkNotNull(expectedDraft)
                    checkNotNull(expectedDisplay)
                    check(
                        persisted.matchesPersistedRating(
                            expectedDraft,
                            expectedDisplay,
                            compareCategories
                        )
                    ) { "Persisted rating verification failed" }
                }
                updateRatingState(target.anime.mediaType, ratingId)
                if (_ratingEditor.value.matches(target.anime, target.isGroup)) _ratingEditor.value = null
                refreshOpenGroup()
                Log.i(
                    RATING_LOG_TAG,
                    "operation=$operation media=${target.anime.mediaType.persistedValue} " +
                        "id=$ratingId group=${target.isGroup} success=true"
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                reportRatingFailure(operation, target, exception)
            }
        }
    }

    private suspend fun loadRatingDraft(target: RatingEditorState, ratingId: String): RatingDraft = when {
        target.isGroup -> genericGroupRepository.loadGroupRating(ratingId, fromMembers = false)
        else -> genericRepository.loadRating(target.anime.mediaType, ratingId)
    }

    private suspend fun reportRatingFailure(
        operation: String,
        target: RatingEditorState,
        exception: Exception
    ) {
        Log.e(
            RATING_LOG_TAG,
            "operation=$operation media=${target.anime.mediaType.persistedValue} " +
                "id=${target.anime.id} group=${target.isGroup} failed=${exception.javaClass.simpleName}: " +
                exception.message,
            exception
        )
        _messages.send(UserMessage.Text(R.string.message_rating_operation_failed))
    }

    private suspend fun refreshOpenGroup() {
        val detail = _groupDetail.value ?: return
        val refreshed = genericGroupRepository.groupDetail(detail.id)?.toUi()
        _groupDetail.value = refreshed
    }

    fun updateShowImages(value: Boolean) {
        viewModelScope.launch {
            if (viewingScope.value == ViewingScope.All) settingsRepository.updateAllShowImages(value)
            else settingsRepository.updateShowImages(value)
        }
    }

    fun updateOneCoverPerGroup(value: Boolean) {
        viewModelScope.launch { settingsRepository.updateOneCoverPerGroup(value) }
    }

    fun completeWelcome(showImages: Boolean, elaborateRating: Boolean) {
        viewModelScope.launch {
            settingsRepository.completeWelcome(showImages, elaborateRating)
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            selectAvailableMediaIfNeeded()
            settingsRepository.completeOnboarding()
        }
    }

    fun downloadCategory(mediaType: MediaType) {
        viewModelScope.launch {
            val action = if (categoryPackManager.states.value[mediaType]?.canRetry == true) {
                PackWorkAction.RECOVER
            } else {
                categoryPackManager.markInstallQueued(mediaType)
                PackWorkAction.INSTALL
            }
            _catalogAvailability.value = _catalogAvailability.value +
                (mediaType to CatalogAvailability.LOADING)
            PackBackgroundWorker.enqueue(getApplication(), mediaType, action)
        }
    }

    fun cancelInitialCoverDownload(mediaType: MediaType) {
        viewModelScope.launch {
            categoryPackManager.requestInitialCoverCancellation(mediaType)
            PackBackgroundWorker.cancel(getApplication(), mediaType)
            coverOperations.cancelInitialPreload(mediaType)
        }
    }

    fun removeCategoryCatalog(mediaType: MediaType) {
        uninstallPackKeepData(mediaType)
    }

    fun uninstallPackKeepData(mediaType: MediaType) {
        viewModelScope.launch {
            PackBackgroundWorker.cancel(getApplication(), mediaType)
            coverOperations.cancelPackAndJoin(mediaType)
            val removed = categoryPackManager.purgePack(
                mediaType = mediaType,
                purgeRuntimeData = {
                    genericRepository.deleteCatalogReferenceData(mediaType)
                    genericRepository.clearSharedCoverIdentities(mediaType)
                },
                finalizeRuntime = { finalizePackRemoval(mediaType) }
            )
            _messages.send(
                UserMessage.Text(
                    if (removed) R.string.category_catalog_removed else R.string.category_pack_invalid
                )
            )
        }
    }

    /** Final destructive pack boundary: all pack-owned files, rows and preferences are purged. */
    fun purgePack(packId: PackId) {
        val mediaType = packId.toOfficialMediaTypeOrNull()
        if (mediaType == null) {
            // Third-party installation UI/import is intentionally deferred, but ownership and
            // filesystem cleanup already use PackId rather than silently leaking unknown packs.
            viewModelScope.launch { categoryPackManager.deleteInstalledFiles(packId) }
            return
        }
        viewModelScope.launch {
            PackBackgroundWorker.cancel(getApplication(), mediaType)
            coverOperations.cancelPackAndJoin(mediaType)
            val purged = categoryPackManager.purgePack(
                mediaType = mediaType,
                purgeRuntimeData = {
                    genericRepository.purgePackData(mediaType)
                    genericRepository.clearSharedCoverIdentities(mediaType)
                    settingsRepository.purgePackSettings(mediaType)
                },
                finalizeRuntime = { finalizePackRemoval(mediaType) }
            )
            _messages.send(
                UserMessage.Text(
                    if (purged) R.string.category_pack_purged else R.string.category_pack_invalid
                )
            )
        }
    }

    private suspend fun finalizePackRemoval(mediaType: MediaType) {
        searchStates.value = searchStates.value +
            (mediaType to SearchUiState(sortOrder = CatalogSortOrder.POPULARITY))
        rankingStates.value = rankingStates.value + (mediaType to RankingUiState())
        _catalogAvailability.value = _catalogAvailability.value +
            (mediaType to CatalogAvailability.UNAVAILABLE)
        if (mediaType == MediaType.ANIME) {
            _catalogCount.value = 0
            _filterOptions.value = AnimeFilterOptions()
        }
        _genericFilterOptions.value = _genericFilterOptions.value +
            (mediaType to GenericMediaFilterOptions())
        selectAvailableMediaIfNeeded()
        invalidatePackRuntimeState(mediaType)
    }

    fun deleteCategoryPersonalData(mediaType: MediaType) {
        viewModelScope.launch {
            genericRepository.deleteAllPersonalData(mediaType)
            settingsRepository.resetMediaSettings(mediaType)
            val visibleClears = searchPageState.value.items
                .filter { it.mediaType == mediaType }
                .associate {
                    ratingOverrideKey(it.mediaType, it.id) to RatingDisplayOverride(null, null, null)
                }
            ratingDisplayOverrides.value = ratingDisplayOverrides.value + visibleClears
            revisions.ratingsChanged(mediaType)
            _messages.send(UserMessage.Text(R.string.category_personal_data_deleted))
        }
    }

    private suspend fun deleteCatalogReferenceData(mediaType: MediaType) {
        genericRepository.deleteCatalogReferenceData(mediaType)
    }

    private suspend fun refreshFilterOptions(mediaType: MediaType) {
        _genericFilterOptions.value = _genericFilterOptions.value +
            (mediaType to genericRepository.filterOptions(mediaType))
        if (mediaType == MediaType.ANIME) {
            val options = _genericFilterOptions.value.getValue(mediaType)
            _catalogCount.value = genericRepository.itemCount(mediaType)
            _filterOptions.value = AnimeFilterOptions(
                genres = options.genres,
                formats = normalizedFormatOptions(options.formats),
                years = options.years,
                studios = options.creditNames
            )
        }
    }

    /**
     * One pack lifecycle boundary for all process-local state derived from catalog rows.
     * Install and purge both come through here so search, ranking, rating editors and covers never
     * require an application restart to observe the new pack generation.
     */
    private fun invalidatePackRuntimeState(mediaType: MediaType) {
        ratingDisplayOverrides.value = emptyMap()
        if (_ratingEditor.value?.anime?.mediaType == mediaType) _ratingEditor.value = null
        if (_groupDetail.value?.mediaType == mediaType) _groupDetail.value = null
        genericCoverRepository.invalidatePackRuntimeState(mediaType)
        genericGroupCoverRepository.invalidatePackRuntimeState(mediaType)
        coverRepository.invalidateAll()
        revisions.catalogChanged(mediaType)
        revisions.ratingsChanged(mediaType)
    }

    private fun updateRatingState(mediaType: MediaType, id: String) {
        ratingDisplayOverrides.value = ratingDisplayOverrides.value - id -
            ratingOverrideKey(mediaType, id)
        revisions.ratingsChanged(mediaType)
    }

    private suspend fun selectAvailableMediaIfNeeded() {
        val active = settings.value.activeMediaType
        resolveAvailableMediaType(active, categoryPackManager.states.value)
            ?.takeIf { it != active }
            ?.let { settingsRepository.updateActiveMediaType(it) }
    }

    fun updateMergeSeasonsEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.updateMergeSeasonsEnabled(value) }
    }

    fun updateStickyScore(value: Boolean) {
        viewModelScope.launch {
            if (viewingScope.value == ViewingScope.All) settingsRepository.updateAllStickyScore(value)
            else settingsRepository.updateStickyScore(value)
        }
    }

    fun updateRatingCategory(category: RatingCategory) {
        viewModelScope.launch {
            val config = settings.value.ratingConfig
            settingsRepository.updateRatingConfig(
                config.copy(categories = config.categories.map { if (it.id == category.id) category else it })
            )
        }
    }

    fun createRatingCategory(category: RatingCategory) {
        viewModelScope.launch {
            val current = settings.value
            settingsRepository.updateRatingSetup(
                current.ratingConfig.copy(categories = current.ratingConfig.categories + category),
                current.ratingProfiles.withCategoryAdded(category.id)
            )
        }
    }

    fun deleteRatingCategory(category: RatingCategory) {
        viewModelScope.launch {
            val current = settings.value
            settingsRepository.updateRatingSetup(
                current.ratingConfig.copy(categories = current.ratingConfig.categories.filterNot { it.id == category.id }),
                current.ratingProfiles.withCategoryRemoved(category.id)
            )
            genericRepository.deleteRatingCategory(current.activeMediaType, category.id)
            genericGroupRepository.deleteGroupRatingCategory(current.activeMediaType, category.id)
        }
    }

    fun updateRatingProfiles(config: RatingProfilesConfig) {
        viewModelScope.launch { settingsRepository.updateRatingProfiles(config) }
    }

    fun deleteRatingProfile(profileId: String) {
        viewModelScope.launch {
            settingsRepository.updateRatingProfiles(settings.value.ratingProfiles.withProfileDeleted(profileId))
        }
    }

    fun updateElaborateRatingEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.updateElaborateRatingEnabled(value) }
    }

    fun updateActiveMediaType(value: MediaType) {
        _viewingScope.value = ViewingScope.Pack(value)
        if (settings.value.activeMediaType == value) return
        viewModelScope.launch { settingsRepository.updateActiveMediaType(value) }
    }

    fun selectAllMedia() {
        if (allAvailable.value) _viewingScope.value = ViewingScope.All
    }

    fun updateAppIcon(value: AppIcon) {
        viewModelScope.launch {
            try {
                settingsRepository.updateAppIcon(value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Log.e("RankOfflineIcon", "Unable to change launcher icon", exception)
                _messages.send(UserMessage.Text(R.string.message_icon_change_failed))
            }
        }
    }

    fun updateThemePalette(value: AppThemePalette) {
        viewModelScope.launch { settingsRepository.updateThemePalette(value) }
    }

    fun updateThemeSelection(value: ThemeSelection) {
        viewModelScope.launch { settingsRepository.updateThemeSelection(value) }
    }

    fun saveCustomTheme(value: CustomAppPalette, selectAfterSave: Boolean) {
        viewModelScope.launch { settingsRepository.saveCustomTheme(value, selectAfterSave) }
    }

    fun deleteCustomTheme(id: String) {
        viewModelScope.launch { settingsRepository.deleteCustomTheme(id) }
    }

    fun saveBuiltInTheme(builtIn: AppThemePalette, value: CustomAppPalette) {
        viewModelScope.launch { settingsRepository.saveBuiltInTheme(builtIn, value) }
    }

    fun deleteBuiltInTheme(builtIn: AppThemePalette) {
        viewModelScope.launch { settingsRepository.deleteBuiltInTheme(builtIn) }
    }

    fun resetBuiltInThemes() {
        viewModelScope.launch { settingsRepository.resetBuiltInThemes() }
    }

    fun updateLanguage(value: SupportedLanguage) {
        viewModelScope.launch { settingsRepository.updateLanguage(value) }
    }

    fun downloadCover(anime: Anime) = coverOperations.download(anime)

    fun downloadTopCovers(limit: Int) = coverOperations.downloadTop(limit)

    fun cancelCoverPreload() = coverOperations.cancelPreload()

    fun setGeneralSettingsVisible(visible: Boolean) =
        coverOperations.settingsVisibilityChanged(visible)

    fun deleteDownloadedCover(anime: Anime, onComplete: (Boolean) -> Unit) =
        coverOperations.delete(anime, onComplete)

    fun clearDownloadedCovers() = coverOperations.clearDownloaded()

    fun exportPersonalData(uri: Uri) {
        launchPersonalDataTransfer(PersonalDataTransferOperation.EXPORTING) {
            services.personalDataBackupManager.export(uri, currentPersonalDataScope())
            _messages.send(UserMessage.Text(R.string.personal_backup_exported))
        }
    }

    fun preparePersonalDataImport(uri: Uri) {
        launchPersonalDataTransfer(
            PersonalDataTransferOperation.VALIDATING_IMPORT,
            failureMessage = R.string.personal_backup_invalid
        ) {
            _backupPreview.value = services.personalDataBackupManager.preview(
                uri,
                allowedPackIds = currentPersonalDataScope()
            )
        }
    }

    fun dismissPersonalDataImport() {
        if (personalDataTransfer.value.active) return
        _backupPreview.value = null
    }

    fun importPersonalData(mode: PersonalDataImportMode) {
        val preview = _backupPreview.value ?: return
        launchPersonalDataTransfer(PersonalDataTransferOperation.IMPORTING) {
            services.personalDataBackupManager.import(preview, mode)
            _backupPreview.value = null
            MediaType.entries.forEach {
                revisions.ratingsChanged(it)
                revisions.catalogChanged(it)
            }
            _messages.send(UserMessage.Text(R.string.personal_backup_imported))
        }
    }

    fun sharePersonalData() {
        launchPersonalDataTransfer(PersonalDataTransferOperation.SHARING) {
            val file = services.personalDataBackupManager.exportForSharing(currentPersonalDataScope())
            _pendingPersonalDataShare.value = FileProvider.getUriForFile(
                getApplication(),
                "${getApplication<Application>().packageName}.personal-backups",
                file
            )
        }
    }

    private fun currentPersonalDataScope(): Set<PackId> = when (val scope = viewingScope.value) {
        is ViewingScope.Pack -> setOf(scope.mediaType.toPackId())
        ViewingScope.All -> readyMediaTypes(categoryPackStates.value)
            .mapTo(linkedSetOf()) { it.toPackId() }
    }.also { require(it.isNotEmpty()) { "No package is available for personal-data transfer" } }

    fun personalDataShareHandled() {
        _pendingPersonalDataShare.value = null
    }

    fun personalDataShareFailed() {
        _pendingPersonalDataShare.value = null
        viewModelScope.launch {
            _messages.send(UserMessage.Text(R.string.personal_backup_failed))
        }
    }

    internal fun loadRankingShare(scope: ViewingScope) {
        rankingShareLoadJob?.cancel()
        _rankingShareData.value = RankingShareData(scope = scope, loading = true)
        rankingShareLoadJob = viewModelScope.launch {
            try {
                val currentSettings = settings.value
                val mediaTypes = when (scope) {
                    is ViewingScope.Pack -> listOf(scope.mediaType).takeIf {
                        categoryPackStates.value[scope.mediaType]?.available == true
                    }.orEmpty()
                    ViewingScope.All -> readyMediaTypes(categoryPackStates.value)
                }
                val entries = when {
                    mediaTypes.isEmpty() -> emptyList()
                    scope is ViewingScope.Pack -> genericRepository.ranking(
                        mediaType = scope.mediaType,
                        filters = GenericMediaFilters(),
                        sortOrder = RankingSortOrder.FINAL_SCORE,
                        categoryId = null,
                        descending = true,
                        mergeEnabled = currentSettings.mediaSettings.getValue(scope.mediaType).mergeEnabled &&
                            scope.mediaType.supportsMerge()
                    )
                    else -> genericRepository.rankingAll(
                        mediaTypes = mediaTypes,
                        filters = GenericMediaFilters(),
                        sortOrder = RankingSortOrder.FINAL_SCORE,
                        categoryId = null,
                        descending = true,
                        mergeByMedia = mediaTypes.associateWith { mediaType ->
                            currentSettings.mediaSettings.getValue(mediaType).mergeEnabled && mediaType.supportsMerge()
                        }
                    )
                }.map(MediaListEntry::toAnimeDisplay).filter { it.finalScore != null }.take(10)
                val coverIdentities = entries.map { it.sharedCoverMediaId ?: it.id }
                val resolvedPaths = genericRepository.localCoverPaths(coverIdentities)
                val formatter = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
                    minimumFractionDigits = 1
                    maximumFractionDigits = 2
                }
                _rankingShareData.value = RankingShareData(
                    scope = scope,
                    items = entries.map { item ->
                        val coverIdentity = item.sharedCoverMediaId ?: item.id
                        RankingShareItem(
                            packId = item.mediaType.toPackId(),
                            canonicalId = item.id,
                            title = item.localizedDisplayTitle(),
                            rating = formatter.format(requireNotNull(item.finalScore)),
                            coverPath = resolvedPaths[coverIdentity] ?: item.localCoverPath
                        )
                    },
                    backgroundPath = services.rankingShareExporter.backgroundFor(scope),
                    loading = false
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Log.e("RankOfflineShare", "Unable to prepare ranking share", exception)
                if (_rankingShareData.value.scope == scope) {
                    _rankingShareData.value = RankingShareData(scope = scope, loading = false, failed = true)
                }
            }
        }
    }

    internal fun exportRankingShare(request: RankingShareRenderRequest) {
        if (_rankingShareExport.value.rendering || request.items.isEmpty()) return
        _rankingShareExport.value = RankingShareExportState(rendering = true)
        viewModelScope.launch {
            try {
                val file = services.rankingShareExporter.export(request)
                _pendingRankingShare.value = FileProvider.getUriForFile(
                    getApplication(),
                    "${getApplication<Application>().packageName}.personal-backups",
                    file
                )
                // Keep Continue disabled until the chooser launch has consumed this URI.
                _rankingShareExport.value = RankingShareExportState(rendering = true, uriReady = true)
            } catch (cancelled: CancellationException) {
                _rankingShareExport.value = RankingShareExportState()
                throw cancelled
            } catch (exception: Exception) {
                Log.e("RankOfflineShare", "Unable to render ranking share", exception)
                _rankingShareExport.value = RankingShareExportState()
                _messages.send(UserMessage.Text(R.string.ranking_share_failed))
            }
        }
    }

    fun rankingShareHandled() {
        _pendingRankingShare.value = null
        _rankingShareExport.value = RankingShareExportState()
    }

    fun rankingShareFailed() {
        rankingShareHandled()
        viewModelScope.launch { _messages.send(UserMessage.Text(R.string.ranking_share_failed)) }
    }

    private fun launchPersonalDataTransfer(
        operation: PersonalDataTransferOperation,
        failureMessage: Int = R.string.personal_backup_failed,
        block: suspend () -> Unit
    ) {
        viewModelScope.launch {
            try {
                val started = personalDataTransferCoordinator.run(operation, block)
                if (!started) _messages.send(UserMessage.Text(R.string.personal_backup_busy))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Log.e("RankOfflineBackup", "Personal data operation $operation failed", exception)
                _messages.send(UserMessage.Text(failureMessage))
            }
        }
    }
    fun resetRatingCategories() {
        viewModelScope.launch { settingsRepository.resetRatingCategories() }
    }

    fun resetRatingProfiles() {
        viewModelScope.launch { settingsRepository.resetRatingProfiles() }
    }
}

internal fun resolveAvailableMediaType(
    activeMediaType: MediaType,
    categoryPacks: Map<MediaType, CategoryPackState>
): MediaType? = activeMediaType.takeIf { categoryPacks[it]?.available == true }
    ?: MediaType.entries.firstOrNull { categoryPacks[it]?.available == true }

private fun readyMediaTypes(categoryPacks: Map<MediaType, CategoryPackState>): List<MediaType> =
    MediaType.entries.filter { categoryPacks[it]?.available == true }

private fun retainedAllSearchPageState(
    previous: SearchPageState,
    query: String,
    allowedMediaTypes: Set<MediaType>
): SearchPageState {
    if (previous.loadingInitial || previous.failed) {
        return SearchPageState(mediaType = null, query = query, loadingInitial = true, hasMore = false)
    }
    val eligibleItems = previous.items.filter { it.mediaType in allowedMediaTypes }
    val retainedItems = if (previous.query == query) eligibleItems else eligibleItems.map { item ->
        item.canonicalTitle?.let { item.copy(title = it, canonicalTitle = null) } ?: item
    }
    return previous.copy(
        mediaType = null,
        query = query,
        items = retainedItems,
        loadingInitial = false,
        loadingMore = false,
        failed = false
    )
}

private fun RatingDraft.matchesPersistedRating(
    expectedDraft: RatingDraft,
    expectedDisplay: RatingDisplayOverride,
    compareCategories: Boolean
): Boolean =
    finalScore.sameRatingValue(expectedDisplay.finalScore) &&
        qualityScore.sameRatingValue(expectedDisplay.qualityScore) &&
        additiveScore.sameRatingValue(expectedDisplay.additiveScore) &&
        notes == expectedDraft.notes &&
        status == expectedDraft.status &&
        profileId == expectedDraft.profileId &&
        (!compareCategories || categoryScores == expectedDraft.categoryScores)

private fun RatingDraft.isEmptyPersistedRating(): Boolean =
    finalScore == null && qualityScore == null && additiveScore == 0.0 && notes.isEmpty() &&
        status == RatingDraft().status && profileId == null && categoryScores.isEmpty()

private fun Double?.sameRatingValue(other: Double?): Boolean = when {
    this == null || other == null -> this == other
    else -> kotlin.math.abs(this - other) < 1e-9
}
