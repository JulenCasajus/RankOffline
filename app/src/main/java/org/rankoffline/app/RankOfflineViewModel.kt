package org.rankoffline.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
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

private const val RATING_LOG_TAG = "RankOfflineRating"

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class RankOfflineViewModel(application: Application) : AndroidViewModel(application) {
    private val startupTiming = StartupTiming()
    private val database = AnimeDatabase.getInstance(application)
    // Stable 0.1.3 stored a small amount of cover metadata outside the database. These adapters
    // run only as an upgrade bridge; catalog/search/rating/group runtime work uses media_*.
    private val legacyAnimeRepository = AnimeRepository(application, database)
    private val legacyCoverMigration = CoverRepository(application, legacyAnimeRepository)
    private val categoryPackManager = CategoryPackManager(application)
    private val genericRepository = GenericMediaRepository(database) { mediaType ->
        categoryPackManager.descriptor(mediaType)?.manifest
    }
    private val genericGroupRepository = GenericMediaGroupRepository(database)
    private val bundledCatalogManager = BundledMediaCatalogManager(application, database)
    private val settingsRepository = SettingsRepository(application)
    val coverRepository = CoverBitmapCache()
    val genericCoverRepository = GenericMediaCoverRepository(
        application,
        database,
        manifestProvider = { mediaType -> categoryPackManager.descriptor(mediaType)?.manifest }
    )
    val genericGroupCoverRepository = GenericMediaGroupCoverRepository(
        application,
        database,
        manifestProvider = { mediaType -> categoryPackManager.descriptor(mediaType)?.manifest }
    )
    private val coverPreloadManager = CoverPreloadManager(genericCoverRepository)

    val settings: StateFlow<AppSettingsState> = settingsRepository.state
    val categoryPackStates: StateFlow<Map<MediaType, CategoryPackState>> = categoryPackManager.states
    val categoryDownloadProgress: StateFlow<CategoryDownloadProgress?> = categoryPackManager.downloadProgress
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
        _messages::send
    )
    val coverPreloadProgress: StateFlow<CoverPreloadProgress> = combine(
        coverOperations.progress,
        settings
    ) { progress, currentSettings ->
        progress.takeIf { it.mediaType == null || it.mediaType == currentSettings.activeMediaType }
            ?: CoverPreloadProgress()
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
    val activeSearchState: StateFlow<SearchUiState> = activeSearchSnapshot
        .map { it.state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())
    private val activeRankingSnapshot: StateFlow<ActiveRankingSnapshot> = combine(rankingStates, settings) { states, currentSettings ->
        val validCategories = currentSettings.ratingConfig.categories.mapTo(mutableSetOf()) { it.id }
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
    val activeRankingState: StateFlow<RankingUiState> = activeRankingSnapshot
        .map { it.state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RankingUiState())
    private val searchPageRequests = MutableSharedFlow<MediaType>(extraBufferCapacity = 1)
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

    val searchPageState: StateFlow<SearchPageState> = searchRequest
        .flatMapLatest { request ->
            flow {
                emit(
                    SearchPageState(
                        mediaType = request.mediaType,
                        loadingInitial = true,
                        hasMore = false
                    )
                )
                var accumulator = SearchPageAccumulator<Anime>()
                try {
                    var page = loadSearchPage(request, INITIAL_SEARCH_PAGE_SIZE, accumulator.nextOffset)
                    accumulator = accumulator.append(page, INITIAL_SEARCH_PAGE_SIZE, Anime::id)
                    emit(
                        SearchPageState(
                            mediaType = request.mediaType,
                            items = accumulator.items,
                            loadingInitial = false,
                            hasMore = accumulator.hasMore
                        )
                    )
                    searchPageRequests.filter { it == request.mediaType }.collect {
                        if (accumulator.hasMore) {
                            emit(
                                SearchPageState(
                                    mediaType = request.mediaType,
                                    items = accumulator.items,
                                    loadingInitial = false,
                                    loadingMore = true,
                                    hasMore = true
                                )
                            )
                            page = loadSearchPage(request, NEXT_SEARCH_PAGE_SIZE, accumulator.nextOffset)
                            accumulator = accumulator.append(page, NEXT_SEARCH_PAGE_SIZE, Anime::id)
                            emit(
                                SearchPageState(
                                    mediaType = request.mediaType,
                                    items = accumulator.items,
                                    loadingInitial = false,
                                    hasMore = accumulator.hasMore
                                )
                            )
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
                    emit(
                        SearchPageState(
                            mediaType = request.mediaType,
                            loadingInitial = false,
                            hasMore = false,
                            failed = true
                        )
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchPageState())

    val searchResults: StateFlow<List<Anime>> = combine(
        searchPageState,
        ratingDisplayOverrides,
        settings
    ) { page, overrides, currentSettings ->
        page.visibleItemsFor(currentSettings.activeMediaType, overrides)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rankingResultState: StateFlow<RankingResultState> = combine(
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
                emit(RankingResultState(mediaType = request.mediaType, loading = true))
                try {
                    emit(
                        RankingResultState(
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
                    )
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Log.e(
                        "RankOfflineCatalog",
                        "operation=ranking media=${request.mediaType.persistedValue} failed=${exception.javaClass.simpleName}: ${exception.message}",
                        exception
                    )
                    emit(
                        RankingResultState(
                            mediaType = request.mediaType,
                            loading = false,
                            failed = true
                        )
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankingResultState())

    val ranking: StateFlow<List<Anime>> = combine(rankingResultState, settings) { result, currentSettings ->
        result.visibleItemsFor(currentSettings.activeMediaType)
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

    private val _ratingEditor = MutableStateFlow<RatingEditorState?>(null)
    val ratingEditor: StateFlow<RatingEditorState?> = _ratingEditor.asStateFlow()

    init {
        startupTiming.mark("ViewModel created")
        viewModelScope.launch {
            try {
                settingsRepository.loadInitialState()
                searchStates.value = searchStates.value.withCatalogSortOrders(
                    settings.value.catalogSortOrders
                )
                startupTiming.mark("DataStore loaded")
                settingsRepository.takeRemovedLegacyCategoryIds().forEach { categoryId ->
                    MediaType.entries.forEach { mediaType ->
                        genericRepository.deleteRatingCategory(mediaType, categoryId)
                        genericGroupRepository.deleteGroupRatingCategory(mediaType, categoryId)
                    }
                }
                categoryPackManager.recoverFilesystem()
                categoryPackManager.refresh()
                startupTiming.mark("category packs refreshed")
                selectAvailableMediaIfNeeded()
                // Pack state and preferences are sufficient to render onboarding/navigation.
                // Large first-install catalog imports continue off the main thread while each
                // category truthfully remains LOADING, instead of blocking the whole app UI.
                _ready.value = true
                startupTiming.mark("first UI state ready")
                legacyCoverMigration.initialize()
                genericRepository.reconcileLegacyAnimeCoverMetadata()
                startupTiming.mark("legacy cover metadata reconciled")
                val activeMediaType = settings.value.activeMediaType
                val importOrder = listOf(activeMediaType) + MediaType.entries.filterNot { it == activeMediaType }
                importOrder.forEach { mediaType ->
                    val availability = categoryPackManager.recoverRuntime(
                        mediaType = mediaType,
                        clearMissingCatalog = { deleteCatalogReferenceData(mediaType) },
                        initializeRuntime = { reportPhase ->
                            importAvailableCategory(
                                mediaType = mediaType,
                                publishRuntimeChange = false,
                                onInstallPhase = reportPhase
                            )
                        }
                    )
                    _catalogAvailability.value = _catalogAvailability.value +
                        (mediaType to availability)
                    if (availability == CatalogAvailability.AVAILABLE) {
                        invalidatePackRuntimeState(mediaType)
                    }
                    if (mediaType == activeMediaType) {
                        startupTiming.mark("first category import resolved")
                    }
                }
                selectAvailableMediaIfNeeded()
                startupTiming.mark("background catalog initialization finished")
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
    }

    fun setSearchQuery(value: String) {
        updateActiveSearchState { it.copy(query = value) }
    }

    fun requestNextSearchPage() {
        searchPageRequests.tryEmit(settings.value.activeMediaType)
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
        val mediaType = settings.value.activeMediaType
        searchStates.value = searchStates.value.updateSearchUiState(mediaType, transform)
    }

    private fun updateActiveCatalogSortOrder(
        transform: (CatalogSortOrder) -> CatalogSortOrder
    ) {
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
        val mediaType = settings.value.activeMediaType
        rankingStates.value = rankingStates.value.updateRankingUiState(mediaType, transform)
    }

    fun openRating(anime: Anime) {
        _ratingEditor.value = RatingEditorState(anime)
        viewModelScope.launch {
            try {
                val draft = loadRatingDraft(RatingEditorState(anime), anime.id)
                if (_ratingEditor.value?.anime?.id == anime.id) {
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
                if (_ratingEditor.value?.anime?.id == target.id) {
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
        val profile = settings.value.ratingProfiles.resolveProfile(profileId)
        val config = settings.value.ratingConfig.withProfile(profile)
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
                if (_ratingEditor.value?.anime?.id == ratingId) _ratingEditor.value = null
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
        viewModelScope.launch { settingsRepository.updateShowImages(value) }
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
            if (categoryPackManager.states.value[mediaType]?.canRetry == true) {
                _catalogAvailability.value = _catalogAvailability.value +
                    (mediaType to CatalogAvailability.LOADING)
                val recovered = categoryPackManager.recoverRuntime(
                    mediaType = mediaType,
                    clearMissingCatalog = { deleteCatalogReferenceData(mediaType) },
                    initializeRuntime = { reportPhase ->
                        importAvailableCategory(
                            mediaType = mediaType,
                            publishRuntimeChange = false,
                            onInstallPhase = reportPhase
                        )
                    }
                )
                _catalogAvailability.value = _catalogAvailability.value + (mediaType to recovered)
                if (recovered == CatalogAvailability.AVAILABLE) {
                    invalidatePackRuntimeState(mediaType)
                    selectAvailableMediaIfNeeded()
                } else {
                    _messages.send(UserMessage.Text(R.string.category_pack_invalid))
                }
                return@launch
            }
            // A pack replacement must never reset a previously chosen cover-sharing setting.
            val isFirstInstallation = categoryPackManager.installedDirectory(mediaType) == null
            // Capture this pack's persisted choices once. Never use the currently selected pack
            // while a different pack is being installed.
            val packSettings = settings.value.mediaSettings.getValue(mediaType)
            when (categoryPackManager.downloadAndInstall(
                mediaType = mediaType,
                importInstalledPack = { reportPhase ->
                    val availability = importAvailableCategory(
                        mediaType = mediaType,
                        publishRuntimeChange = false,
                        onInstallPhase = reportPhase
                    )
                    availability == CatalogAvailability.AVAILABLE
                },
                finalizeRuntime = {
                    invalidatePackRuntimeState(mediaType)
                },
                downloadInitialCovers = packSettings.showImages,
                acquireInitialCovers = { reportProgress ->
                    coverOperations.preloadInitialTop(
                        mediaType = mediaType,
                        limit = CategoryPackManager.INITIAL_COVER_TARGET,
                        oneCoverPerGroup = packSettings.oneCoverPerGroup,
                        onProgress = reportProgress
                    )
                }
            )) {
                CategoryPackDownloadResult.INSTALLED -> {
                    if (isFirstInstallation) {
                        categoryPackManager.descriptor(mediaType)?.manifest?.defaults?.let { defaults ->
                            settingsRepository.applyNewPackDefaults(mediaType, defaults)
                        }
                    }
                    selectAvailableMediaIfNeeded()
                    val covers = categoryPackManager.states.value[mediaType]?.initialCoverAcquisition
                    _messages.send(
                        when (covers?.status) {
                            InitialCoverAcquisitionStatus.PARTIAL -> UserMessage.Text(
                                R.string.category_cover_partial,
                                listOf(covers.available, covers.target, covers.unavailable + covers.errors)
                            )
                            InitialCoverAcquisitionStatus.CANCELLED -> UserMessage.Text(
                                R.string.category_cover_cancelled,
                                listOf(covers.available, covers.target)
                            )
                            else -> UserMessage.Text(R.string.category_download_complete)
                        }
                    )
                }
                CategoryPackDownloadResult.UNAVAILABLE ->
                    _messages.send(UserMessage.Text(R.string.category_download_unavailable))
                CategoryPackDownloadResult.NETWORK_ERROR ->
                    _messages.send(UserMessage.Text(R.string.message_no_internet))
                CategoryPackDownloadResult.INVALID_PACK ->
                    _messages.send(UserMessage.Text(R.string.category_pack_invalid))
            }
        }
    }

    fun cancelInitialCoverDownload(mediaType: MediaType) {
        coverOperations.cancelInitialPreload(mediaType)
    }

    fun removeCategoryCatalog(mediaType: MediaType) {
        purgePack(mediaType.toPackId())
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
            coverOperations.cancelAllAndJoin()
            val purged = categoryPackManager.purgePack(
                mediaType = mediaType,
                purgeRuntimeData = {
                    genericRepository.purgePackData(mediaType)
                    settingsRepository.purgePackSettings(mediaType)
                },
                finalizeRuntime = {
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
            )
            _messages.send(
                UserMessage.Text(
                    if (purged) R.string.category_pack_purged else R.string.category_pack_invalid
                )
            )
        }
    }

    fun deleteCategoryPersonalData(mediaType: MediaType) {
        viewModelScope.launch {
            genericRepository.deleteAllPersonalData(mediaType)
            settingsRepository.resetMediaSettings(mediaType)
            val visibleClears = searchPageState.value.items
                .filter { it.mediaType == mediaType }
                .associate { it.id to RatingDisplayOverride(null, null, null) }
            ratingDisplayOverrides.value = ratingDisplayOverrides.value + visibleClears
            revisions.ratingsChanged(mediaType)
            _messages.send(UserMessage.Text(R.string.category_personal_data_deleted))
        }
    }

    private suspend fun deleteCatalogReferenceData(mediaType: MediaType) {
        genericRepository.deleteCatalogReferenceData(mediaType)
    }

    private suspend fun importAvailableCategory(
        mediaType: MediaType,
        publishRuntimeChange: Boolean = true,
        onInstallPhase: (CategoryPackInstallPhase) -> Unit = {}
    ): CatalogAvailability {
        val directory = categoryPackManager.installedDirectory(mediaType)
        val availability = bundledCatalogManager.importOptional(
                mediaType = mediaType,
                packManifest = categoryPackManager.descriptor(mediaType)?.manifest,
                onPhase = onInstallPhase
            ).also { result ->
                if (result == CatalogAvailability.AVAILABLE) {
                    if (directory != null) {
                        categoryPackManager.descriptor(mediaType)?.manifest?.let { manifest ->
                            genericRepository.installPackCovers(manifest, directory)
                        }
                    }
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
            }
        _catalogAvailability.value = _catalogAvailability.value + (mediaType to availability)
        if (publishRuntimeChange) invalidatePackRuntimeState(mediaType)
        return availability
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
        ratingDisplayOverrides.value = ratingDisplayOverrides.value - id
        revisions.ratingsChanged(mediaType)
    }

    private suspend fun selectAvailableMediaIfNeeded() {
        val active = settings.value.activeMediaType
        if (categoryPackManager.states.value[active]?.available == true) return
        MediaType.entries.firstOrNull { categoryPackManager.states.value[it]?.available == true }
            ?.let { settingsRepository.updateActiveMediaType(it) }
    }

    fun updateMergeSeasonsEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.updateMergeSeasonsEnabled(value) }
    }

    fun updateStickyScore(value: Boolean) {
        viewModelScope.launch { settingsRepository.updateStickyScore(value) }
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
        if (settings.value.activeMediaType == value) return
        viewModelScope.launch { settingsRepository.updateActiveMediaType(value) }
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
    fun resetRatingCategories() {
        viewModelScope.launch { settingsRepository.resetRatingCategories() }
    }

    fun resetRatingProfiles() {
        viewModelScope.launch { settingsRepository.resetRatingProfiles() }
    }
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
