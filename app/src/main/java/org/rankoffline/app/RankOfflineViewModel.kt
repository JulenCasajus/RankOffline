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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class RatingEditorState(val anime: Anime, val draft: RatingDraft? = null)

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
    private val dataVersion = MutableStateFlow(0L)

    val searchResults: StateFlow<List<Anime>> = combine(
        query.debounce(300),
        dataVersion,
        _catalogSortOrder
    ) { currentQuery, _, sortOrder -> currentQuery to sortOrder }
        .mapLatest { (currentQuery, sortOrder) -> repository.search(currentQuery, sortOrder) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val ranking: StateFlow<List<Anime>> = repository.observeRanking()
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

    fun openRating(anime: Anime) {
        _ratingEditor.value = RatingEditorState(anime)
        viewModelScope.launch {
            val draft = repository.loadRating(anime.id)
            if (_ratingEditor.value?.anime?.id == anime.id) {
                _ratingEditor.value = RatingEditorState(anime, draft)
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
        viewModelScope.launch {
            val profile = settings.value.ratingProfiles.resolveProfile(profileId)
            repository.saveElaborateRating(
                animeId,
                draft,
                settings.value.ratingConfig.withProfile(profile),
                finalScoreOverride,
                profile.id
            )
            dataVersion.value++
            _ratingEditor.value = null
        }
    }

    fun saveSimpleRating(animeId: String, draft: RatingDraft, qualityScore: Double, finalScore: Double) {
        viewModelScope.launch {
            repository.saveSimpleRating(animeId, draft, qualityScore, finalScore)
            dataVersion.value++
            _ratingEditor.value = null
        }
    }

    fun deleteRating(animeId: String) {
        viewModelScope.launch {
            repository.deleteRating(animeId)
            dataVersion.value++
            _ratingEditor.value = null
        }
    }

    fun updateShowImages(value: Boolean) {
        viewModelScope.launch { settingsRepository.updateShowImages(value) }
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
