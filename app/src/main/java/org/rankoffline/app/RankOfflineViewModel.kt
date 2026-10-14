package org.rankoffline.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class RatingEditorState(
    val anime: Anime,
    val isGroup: Boolean = false,
    val draft: RatingDraft? = null
)
private data class CatalogRequest(
    val mediaType: MediaType,
    val query: String,
    val sort: CatalogSortOrder,
    val animeFilters: AnimeFilters,
    val genericFilters: GenericMediaFilters,
    val merge: Boolean
)
private data class ActiveSearchSnapshot(
    val mediaType: MediaType,
    val state: SearchUiState,
    val merge: Boolean
)
private data class ActiveRankingSnapshot(
    val mediaType: MediaType,
    val state: RankingUiState,
    val merge: Boolean
)
private data class RankingRequest(
    val mediaType: MediaType,
    val animeFilters: AnimeFilters,
    val genericFilters: GenericMediaFilters,
    val sort: RankingSortOrder,
    val descending: Boolean,
    val categoryId: String?,
    val merge: Boolean
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class RankOfflineViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AnimeDatabase.getInstance(application)
    private val repository = AnimeRepository(application, database)
    private val genericRepository = GenericMediaRepository(database)
    private val genericGroupRepository = GenericMediaGroupRepository(database)
    private val bundledCatalogManager = BundledMediaCatalogManager(application, database)
    private val settingsRepository = SettingsRepository(application)
    val coverRepository = CoverRepository(application, repository)
    private val genericCoverRepository = GenericMediaCoverRepository(application, database)
    private val coverPreloadManager = CoverPreloadManager(repository, coverRepository)

    val settings: StateFlow<AppSettingsState> = settingsRepository.state
    val coverPreloadProgress: StateFlow<CoverPreloadProgress> = coverPreloadManager.progress

    private val _messages = Channel<UserMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private var preloadJob: Job? = null
    private val manualDownloadJobs = ConcurrentHashMap<String, Job>()

    private val searchStates = MutableStateFlow(defaultSearchUiStates())
    private val rankingStates = MutableStateFlow(defaultRankingUiStates())
    private val activeSearchSnapshot: StateFlow<ActiveSearchSnapshot> = combine(searchStates, settings) { states, currentSettings ->
        ActiveSearchSnapshot(
            mediaType = currentSettings.activeMediaType,
            state = states.searchStateFor(currentSettings.activeMediaType),
            merge = currentSettings.mergeEnabled
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
            merge = currentSettings.mergeEnabled
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ActiveRankingSnapshot(MediaType.ANIME, RankingUiState(), merge = false)
    )
    val activeRankingState: StateFlow<RankingUiState> = activeRankingSnapshot
        .map { it.state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RankingUiState())
    private val _filterOptions = MutableStateFlow(AnimeFilterOptions())
    val filterOptions: StateFlow<AnimeFilterOptions> = _filterOptions.asStateFlow()
    private val _genericFilterOptions = MutableStateFlow(
        mapOf(
            MediaType.MOVIE to GenericMediaFilterOptions(),
            MediaType.SERIES to GenericMediaFilterOptions()
        )
    )
    val genericFilterOptions: StateFlow<Map<MediaType, GenericMediaFilterOptions>> =
        _genericFilterOptions.asStateFlow()
    private val _catalogAvailability = MutableStateFlow(
        MediaType.entries.associateWith { type ->
            when (type) {
                MediaType.ANIME, MediaType.MOVIE, MediaType.SERIES -> CatalogAvailability.LOADING
                else -> CatalogAvailability.UNAVAILABLE
            }
        }
    )
    val catalogAvailability: StateFlow<Map<MediaType, CatalogAvailability>> =
        _catalogAvailability.asStateFlow()
    private val dataVersion = MutableStateFlow(0L)
    private val _groupDetail = MutableStateFlow<GroupDetailUi?>(null)
    val groupDetail: StateFlow<GroupDetailUi?> = _groupDetail.asStateFlow()

    private val debouncedSearchQuery = activeSearchSnapshot
        .map { it.mediaType to it.state.query }
        .distinctUntilChanged()
        .debounce(300)

    val searchResults: StateFlow<List<Anime>> = combine(
        debouncedSearchQuery,
        dataVersion,
        activeSearchSnapshot
    ) { (queryMediaType, currentQuery), _, active ->
        if (queryMediaType != active.mediaType) {
            null
        } else {
            CatalogRequest(
                active.mediaType,
                currentQuery,
                active.state.sortOrder,
                active.state.filters,
                active.state.genericFilters,
                active.merge
            )
        }
    }
        .filterNotNull()
        .mapLatest { request ->
            when (request.mediaType.dataPath()) {
                MediaDataPath.ANIME -> repository.search(
                    request.query,
                    request.sort,
                    request.animeFilters,
                    mergeSeasonsEnabled = request.merge
                )
                MediaDataPath.GENERIC -> genericRepository.search(
                    request.mediaType,
                    request.query,
                    request.sort,
                    request.genericFilters,
                    request.merge
                ).map(MediaListEntry::toAnimeDisplay)
                MediaDataPath.UNSUPPORTED -> emptyList()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val ranking: StateFlow<List<Anime>> = combine(activeRankingSnapshot, dataVersion) { active, _ ->
            RankingRequest(
                active.mediaType,
                active.state.filters,
                active.state.genericFilters,
                active.state.sortOrder,
                active.state.descending,
                active.state.categoryId,
                active.merge
            )
        }
        .flatMapLatest { request ->
            when (request.mediaType.dataPath()) {
                MediaDataPath.ANIME -> repository.observeRanking(
                    request.animeFilters,
                    request.sort,
                    request.categoryId,
                    descending = request.descending,
                    mergeSeasonsEnabled = request.merge
                )
                MediaDataPath.GENERIC -> flow {
                    emit(
                        genericRepository.ranking(
                            request.mediaType,
                            request.genericFilters,
                            request.sort,
                            request.categoryId,
                            request.descending,
                            request.merge
                        ).map(MediaListEntry::toAnimeDisplay)
                    )
                }
                MediaDataPath.UNSUPPORTED -> flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloadedCoverCount: StateFlow<Int> = repository.observeDownloadedCoverCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _catalogCount = MutableStateFlow(0)
    val catalogCount: StateFlow<Int> = _catalogCount.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _errorMessage = MutableStateFlow<UserMessage?>(null)
    val errorMessage: StateFlow<UserMessage?> = _errorMessage.asStateFlow()

    private val _ratingEditor = MutableStateFlow<RatingEditorState?>(null)
    val ratingEditor: StateFlow<RatingEditorState?> = _ratingEditor.asStateFlow()

    private var generalSettingsVisible = false

    init {
        viewModelScope.launch {
            try {
                settingsRepository.loadInitialState()
                settingsRepository.takeRemovedLegacyCategoryIds().forEach { categoryId ->
                    repository.deleteRatingCategory(categoryId)
                }
                repository.updateCatalogIfNeeded()
                coverRepository.initialize()
                _catalogCount.value = repository.animeCount()
                _filterOptions.value = repository.filterOptions()
                _catalogAvailability.value = _catalogAvailability.value +
                    (MediaType.ANIME to CatalogAvailability.AVAILABLE)
                for (mediaType in listOf(MediaType.MOVIE, MediaType.SERIES)) {
                    val availability = bundledCatalogManager.importOptional(mediaType)
                    _catalogAvailability.value = _catalogAvailability.value +
                        (mediaType to availability)
                    if (availability == CatalogAvailability.AVAILABLE) {
                        _genericFilterOptions.value = _genericFilterOptions.value +
                            (mediaType to genericRepository.filterOptions(mediaType))
                    }
                }
                dataVersion.value++
            } catch (exception: Exception) {
                _errorMessage.value = UserMessage.Text(R.string.error_initialize_local_data)
            } finally {
                _ready.value = true
            }
        }
    }

    fun setSearchQuery(value: String) {
        updateActiveSearchState { it.copy(query = value) }
    }

    fun setCatalogSortOrder(value: CatalogSortOrder) {
        updateActiveSearchState { it.copy(sortOrder = value) }
    }

    fun setCatalogSortCriterion(value: CatalogSortOrder) {
        updateActiveSearchState { it.copy(sortOrder = value.withReversed(it.sortOrder.isReversed)) }
    }

    fun toggleCatalogSortDirection() {
        updateActiveSearchState { it.copy(sortOrder = it.sortOrder.inverted()) }
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

    private fun updateActiveRankingState(transform: (RankingUiState) -> RankingUiState) {
        val mediaType = settings.value.activeMediaType
        rankingStates.value = rankingStates.value.updateRankingUiState(mediaType, transform)
    }

    fun openRating(anime: Anime) {
        _ratingEditor.value = RatingEditorState(anime)
        viewModelScope.launch {
            val draft = if (anime.mediaType == MediaType.ANIME) {
                repository.loadRating(anime.id)
            } else {
                genericRepository.loadRating(anime.id)
            }
            if (_ratingEditor.value?.anime?.id == anime.id) {
                _ratingEditor.value = RatingEditorState(anime, draft = draft)
            }
        }
    }

    fun openAnime(anime: Anime) {
        if (anime.groupId == null) {
            openRating(anime)
        } else {
            viewModelScope.launch {
                val detail = if (anime.mediaType == MediaType.ANIME) {
                    repository.groupDetail(anime.groupId)?.toUi()
                } else {
                    genericGroupRepository.groupDetail(anime.groupId)?.toUi()
                }
                detail?.let { openGroupRating(it, fromMembers = false) }
            }
        }
    }

    fun openGroupDetails(anime: Anime) {
        val groupId = anime.groupId ?: return
        viewModelScope.launch {
            _groupDetail.value = if (anime.mediaType == MediaType.ANIME) {
                repository.groupDetail(groupId)?.toUi()
            } else {
                genericGroupRepository.groupDetail(groupId)?.toUi()
            }
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
            finalScore = detail.explicitFinalScore
        )
        _ratingEditor.value = RatingEditorState(target, isGroup = true)
        viewModelScope.launch {
            val draft = if (detail.mediaType == MediaType.ANIME) {
                repository.loadGroupRating(detail.id, fromMembers)
            } else {
                genericGroupRepository.loadGroupRating(detail.id, fromMembers)
            }
            if (_ratingEditor.value?.anime?.id == target.id) {
                _ratingEditor.value = RatingEditorState(target, isGroup = true, draft = draft)
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
        viewModelScope.launch {
            val profile = settings.value.ratingProfiles.resolveProfile(profileId)
            val config = settings.value.ratingConfig.withProfile(profile)
            when {
                target.anime.mediaType == MediaType.ANIME && target.isGroup ->
                    repository.saveElaborateGroupRating(animeId, draft, config, finalScoreOverride, profile.id)
                target.anime.mediaType == MediaType.ANIME ->
                    repository.saveElaborateRating(animeId, draft, config, finalScoreOverride, profile.id)
                target.isGroup -> genericGroupRepository.saveElaborateGroupRating(
                    animeId, draft, config, finalScoreOverride, profile.id
                )
                else -> genericRepository.saveElaborateRating(
                    animeId, draft, config, finalScoreOverride, profile.id
                )
            }
            dataVersion.value++
            _ratingEditor.value = null
            refreshOpenGroup()
        }
    }

    fun saveSimpleRating(animeId: String, draft: RatingDraft, qualityScore: Double, finalScore: Double) {
        val target = _ratingEditor.value ?: return
        viewModelScope.launch {
            when {
                target.anime.mediaType == MediaType.ANIME && target.isGroup ->
                    repository.saveSimpleGroupRating(animeId, draft, qualityScore, finalScore)
                target.anime.mediaType == MediaType.ANIME ->
                    repository.saveSimpleRating(animeId, draft, qualityScore, finalScore)
                target.isGroup -> genericGroupRepository.saveSimpleGroupRating(
                    animeId, draft, qualityScore, finalScore
                )
                else -> genericRepository.saveSimpleRating(animeId, draft, qualityScore, finalScore)
            }
            dataVersion.value++
            _ratingEditor.value = null
            refreshOpenGroup()
        }
    }

    fun deleteRating(animeId: String) {
        val target = _ratingEditor.value ?: return
        viewModelScope.launch {
            when {
                target.anime.mediaType == MediaType.ANIME && target.isGroup ->
                    repository.deleteGroupRating(animeId)
                target.anime.mediaType == MediaType.ANIME -> repository.deleteRating(animeId)
                target.isGroup -> genericGroupRepository.deleteGroupRating(animeId)
                else -> genericRepository.deleteRating(animeId)
            }
            dataVersion.value++
            _ratingEditor.value = null
            refreshOpenGroup()
        }
    }

    private suspend fun refreshOpenGroup() {
        val detail = _groupDetail.value ?: return
        _groupDetail.value = if (detail.mediaType == MediaType.ANIME) {
            repository.groupDetail(detail.id)?.toUi()
        } else {
            genericGroupRepository.groupDetail(detail.id)?.toUi()
        }
    }

    fun updateShowImages(value: Boolean) {
        viewModelScope.launch { settingsRepository.updateShowImages(value) }
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
            if (current.activeMediaType == MediaType.ANIME) {
                repository.deleteRatingCategory(category.id)
            } else {
                genericRepository.deleteRatingCategory(current.activeMediaType, category.id)
                genericGroupRepository.deleteGroupRatingCategory(current.activeMediaType, category.id)
            }
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
        viewModelScope.launch { settingsRepository.updateAppIcon(value) }
    }

    fun updateLanguage(value: SupportedLanguage) {
        viewModelScope.launch { settingsRepository.updateLanguage(value) }
    }

    fun downloadCover(anime: Anime) {
        if (manualDownloadJobs.containsKey(anime.id)) return
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = if (anime.mediaType == MediaType.ANIME) {
                    coverRepository.downloadCover(anime)
                } else {
                    genericCoverRepository.downloadCover(anime.id)
                }
                when (result) {
                    CoverDownloadResult.NetworkError -> _messages.send(UserMessage.Text(R.string.message_no_internet))
                    CoverDownloadResult.NotFound -> _messages.send(UserMessage.Text(R.string.message_cover_not_found))
                    CoverDownloadResult.TemporaryError ->
                        _messages.send(UserMessage.Text(R.string.message_cover_download_failed))
                    CoverDownloadResult.Downloaded -> dataVersion.value++
                    CoverDownloadResult.AlreadyAvailable,
                    CoverDownloadResult.AlreadyInProgress -> Unit
                }
            } finally {
                manualDownloadJobs.remove(anime.id, job)
            }
        }
        val previous = manualDownloadJobs.putIfAbsent(anime.id, job)
        if (previous == null) job.start() else job.cancel()
    }

    fun downloadPopularCovers(batch: PopularCoverBatch) {
        if (preloadJob?.isActive == true || coverPreloadProgress.value.isActive) return
        startPreload(batch.count)
    }

    fun setGeneralSettingsVisible(visible: Boolean) {
        generalSettingsVisible = visible
        if (!visible) clearTerminalTopUpdateState()
    }

    private fun clearTerminalTopUpdateState() {
        if (coverPreloadProgress.value.shouldClearAfterLeavingSettings()) {
            coverPreloadManager.resetProgress()
        }
    }

    fun deleteDownloadedCover(anime: Anime, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val deleted = coverRepository.deleteDownloadedCover(anime)
            if (deleted) {
                dataVersion.value++
                _messages.send(UserMessage.Text(R.string.message_cover_deleted))
            } else {
                _messages.send(UserMessage.Text(R.string.message_cover_delete_failed))
            }
            onComplete(deleted)
        }
    }

    fun clearDownloadedCovers() {
        viewModelScope.launch {
            preloadJob?.cancelAndJoin()
            val manualJobs = manualDownloadJobs.values.toList()
            manualJobs.forEach { it.cancel() }
            manualJobs.forEach { it.join() }
            val removed = coverRepository.clearDownloadedCovers()
            dataVersion.value++
            coverPreloadManager.resetProgress()
            _messages.send(
                if (removed == 1) UserMessage.Text(R.string.message_cover_deleted)
                else UserMessage.Plural(R.plurals.message_covers_deleted, removed, listOf(removed))
            )
        }
    }

    private fun startPreload(count: Int) {
        val previous = preloadJob
        preloadJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            coverPreloadManager.preload(count)
            dataVersion.value++
            if (!generalSettingsVisible) clearTerminalTopUpdateState()
            coverPreloadProgress.value.message?.let { _messages.send(it) }
        }
    }

    fun resetRatingCategories() {
        viewModelScope.launch { settingsRepository.resetRatingCategories() }
    }

    fun resetRatingProfiles() {
        viewModelScope.launch { settingsRepository.resetRatingProfiles() }
    }
}
