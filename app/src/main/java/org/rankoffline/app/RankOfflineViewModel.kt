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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class RatingEditorState(val anime: Anime, val draft: RatingDraft? = null)
private data class CatalogRequest(val query: String, val sort: CatalogSortOrder, val filters: AnimeFilters, val merge: Boolean)
private data class RankingRequest(
    val filters: AnimeFilters,
    val sort: RankingSortOrder,
    val descending: Boolean,
    val categoryId: String?,
    val merge: Boolean
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class RankOfflineViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AnimeDatabase.getInstance(application)
    private val repository = AnimeRepository(application, database)
    private val settingsRepository = SettingsRepository(application)
    val coverRepository = CoverRepository(application, repository)
    private val coverPreloadManager = CoverPreloadManager(repository, coverRepository)

    val settings: StateFlow<AppSettingsState> = settingsRepository.state
    val coverPreloadProgress: StateFlow<CoverPreloadProgress> = coverPreloadManager.progress

    private val _messages = Channel<UserMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private var preloadJob: Job? = null
    private val manualDownloadJobs = ConcurrentHashMap<String, Job>()

    private val query = MutableStateFlow("")
    val searchQuery: StateFlow<String> = query.asStateFlow()
    private val _catalogSortOrder = MutableStateFlow(CatalogSortOrder.POPULARITY)
    val catalogSortOrder: StateFlow<CatalogSortOrder> = _catalogSortOrder.asStateFlow()
    private val _catalogFilters = MutableStateFlow(AnimeFilters())
    val catalogFilters: StateFlow<AnimeFilters> = _catalogFilters.asStateFlow()
    private val _rankingFilters = MutableStateFlow(AnimeFilters())
    val rankingFilters: StateFlow<AnimeFilters> = _rankingFilters.asStateFlow()
    private val _rankingSortOrder = MutableStateFlow(RankingSortOrder.FINAL_SCORE)
    val rankingSortOrder: StateFlow<RankingSortOrder> = _rankingSortOrder.asStateFlow()
    private val _rankingSortDescending = MutableStateFlow(true)
    val rankingSortDescending: StateFlow<Boolean> = _rankingSortDescending.asStateFlow()
    private val _rankingCategoryId = MutableStateFlow<String?>(null)
    val rankingCategoryId: StateFlow<String?> = _rankingCategoryId.asStateFlow()
    private val _filterOptions = MutableStateFlow(AnimeFilterOptions())
    val filterOptions: StateFlow<AnimeFilterOptions> = _filterOptions.asStateFlow()
    private val dataVersion = MutableStateFlow(0L)
    private val _groupDetail = MutableStateFlow<GroupDetail?>(null)
    val groupDetail: StateFlow<GroupDetail?> = _groupDetail.asStateFlow()

    val searchResults: StateFlow<List<Anime>> = combine(
        query.debounce(300),
        dataVersion,
        _catalogSortOrder,
        _catalogFilters,
        settings
    ) { currentQuery, _, sortOrder, filters, currentSettings ->
        CatalogRequest(currentQuery, sortOrder, filters, currentSettings.mergeSeasonsEnabled)
    }
        .mapLatest { request -> repository.search(request.query, request.sort, request.filters, mergeSeasonsEnabled = request.merge) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val ranking: StateFlow<List<Anime>> = combine(
        _rankingFilters,
        _rankingSortOrder,
        _rankingSortDescending,
        _rankingCategoryId,
        settings
    ) { filters, sortOrder, descending, categoryId, currentSettings ->
        RankingRequest(filters, sortOrder, descending, categoryId, currentSettings.mergeSeasonsEnabled)
    }
        .flatMapLatest { request ->
            repository.observeRanking(
                request.filters,
                request.sort,
                request.categoryId,
                descending = request.descending,
                mergeSeasonsEnabled = request.merge
            )
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
                dataVersion.value++
            } catch (exception: Exception) {
                _errorMessage.value = UserMessage.Text(R.string.error_initialize_local_data)
            } finally {
                _ready.value = true
            }
        }
    }

    fun setSearchQuery(value: String) {
        query.value = value
    }

    fun setCatalogSortOrder(value: CatalogSortOrder) {
        _catalogSortOrder.value = value
    }

    fun setCatalogSortCriterion(value: CatalogSortOrder) {
        _catalogSortOrder.value = value.withReversed(_catalogSortOrder.value.isReversed)
    }

    fun toggleCatalogSortDirection() {
        _catalogSortOrder.value = _catalogSortOrder.value.inverted()
    }

    fun setCatalogFilters(value: AnimeFilters) {
        _catalogFilters.value = value
    }

    fun setRankingFilters(value: AnimeFilters) {
        _rankingFilters.value = value
    }

    fun setRankingSortOrder(value: RankingSortOrder) {
        _rankingSortOrder.value = value
        if (value != RankingSortOrder.CATEGORY_SCORE) _rankingCategoryId.value = null
    }

    fun toggleRankingSortDirection() {
        _rankingSortDescending.value = !_rankingSortDescending.value
    }

    fun setRankingCategory(categoryId: String) {
        _rankingCategoryId.value = categoryId
        _rankingSortOrder.value = RankingSortOrder.CATEGORY_SCORE
    }

    fun openRating(anime: Anime) {
        _ratingEditor.value = RatingEditorState(anime)
        viewModelScope.launch {
            val draft = repository.loadRating(anime.id)
            if (_ratingEditor.value?.anime?.id == anime.id) {
                _ratingEditor.value = RatingEditorState(anime, draft)
            }
        }
    }

    fun openAnime(anime: Anime) {
        if (anime.groupId == null) {
            openRating(anime)
        } else {
            viewModelScope.launch {
                repository.groupDetail(anime.groupId)?.let { openGroupRating(it, fromMembers = false) }
            }
        }
    }

    fun openGroupDetails(anime: Anime) {
        val groupId = anime.groupId ?: return
        viewModelScope.launch { _groupDetail.value = repository.groupDetail(groupId) }
    }

    fun closeGroup() { _groupDetail.value = null }

    fun rateGroup(fromMembers: Boolean) {
        val detail = _groupDetail.value ?: return
        openGroupRating(detail, fromMembers)
    }

    private fun openGroupRating(detail: GroupDetail, fromMembers: Boolean) {
        val root = detail.members.firstOrNull { it.id == detail.group.rootAnimeId } ?: return
        val target = root.copy(
            id = detail.group.id, title = detail.group.displayTitle,
            groupId = detail.group.id, groupType = detail.group.type,
            rootAnimeId = detail.group.rootAnimeId,
            finalScore = detail.explicitRating?.finalScore,
            baseScore = detail.explicitRating?.qualityScore,
            additiveScore = detail.explicitRating?.additiveScore
        )
        _ratingEditor.value = RatingEditorState(target)
        viewModelScope.launch {
            val draft = repository.loadGroupRating(detail.group.id, fromMembers)
            if (_ratingEditor.value?.anime?.id == target.id) _ratingEditor.value = RatingEditorState(target, draft)
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
        viewModelScope.launch {
            val profile = settings.value.ratingProfiles.resolveProfile(profileId)
            if (animeId.startsWith("group:"))
                repository.saveElaborateGroupRating(animeId, draft, settings.value.ratingConfig.withProfile(profile), finalScoreOverride, profile.id)
            else repository.saveElaborateRating(animeId, draft, settings.value.ratingConfig.withProfile(profile), finalScoreOverride, profile.id)
            dataVersion.value++
            _ratingEditor.value = null
            _groupDetail.value?.let { _groupDetail.value = repository.groupDetail(it.group.id) }
        }
    }

    fun saveSimpleRating(animeId: String, draft: RatingDraft, qualityScore: Double, finalScore: Double) {
        viewModelScope.launch {
            if (animeId.startsWith("group:"))
                repository.saveSimpleGroupRating(animeId, draft, qualityScore, finalScore)
            else repository.saveSimpleRating(animeId, draft, qualityScore, finalScore)
            dataVersion.value++
            _ratingEditor.value = null
            _groupDetail.value?.let { _groupDetail.value = repository.groupDetail(it.group.id) }
        }
    }

    fun deleteRating(animeId: String) {
        viewModelScope.launch {
            if (animeId.startsWith("group:")) repository.deleteGroupRating(animeId)
            else repository.deleteRating(animeId)
            dataVersion.value++
            _ratingEditor.value = null
            _groupDetail.value?.let { _groupDetail.value = repository.groupDetail(it.group.id) }
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
            repository.deleteRatingCategory(category.id)
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
                when (coverRepository.downloadCover(anime)) {
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
        viewModelScope.launch {
            settingsRepository.resetRatingCategories()
            repository.deleteRatingScoresOutsideCategories(
                DefaultSettings.defaultRatingCategories.map { it.id }
            )
        }
    }

    fun resetRatingProfiles() {
        viewModelScope.launch { settingsRepository.resetRatingProfiles() }
    }
}
