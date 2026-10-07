package com.animerank.offline

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
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

    private val _messages = Channel<String>(Channel.BUFFERED)
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

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _ratingEditor = MutableStateFlow<RatingEditorState?>(null)
    val ratingEditor: StateFlow<RatingEditorState?> = _ratingEditor.asStateFlow()

    private val recalculateRequests = MutableSharedFlow<RatingSystemConfig>(extraBufferCapacity = 1)
    private var generalSettingsVisible = false

    init {
        viewModelScope.launch {
            recalculateRequests.debounce(350).collect { config ->
                repository.recalculateAllRatings(config)
                dataVersion.value++
            }
        }
        viewModelScope.launch {
            try {
                settingsRepository.loadInitialState()
                repository.updateCatalogIfNeeded()
                repository.recalculateAllRatings(settingsRepository.state.value.ratingConfig)
                coverRepository.initialize()
                _catalogCount.value = repository.animeCount()
                dataVersion.value++
            } catch (exception: Exception) {
                _errorMessage.value = exception.message ?: "Unable to initialize local data"
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

    fun saveRating(animeId: String, draft: RatingDraft) {
        viewModelScope.launch {
            repository.saveRating(animeId, draft, settings.value.ratingConfig)
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

    fun updateRatingConfig(config: RatingSystemConfig) {
        viewModelScope.launch {
            settingsRepository.updateRatingConfig(config)
            recalculateRequests.emit(config)
        }
    }

    fun downloadCover(anime: Anime) {
        if (manualDownloadJobs.containsKey(anime.id)) return
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                when (coverRepository.downloadCover(anime)) {
                    CoverDownloadResult.NetworkError -> _messages.send("Sin conexión a Internet")
                    CoverDownloadResult.NotFound -> _messages.send("No se encontró una portada para este anime")
                    CoverDownloadResult.TemporaryError ->
                        _messages.send("No se pudo descargar la portada. Inténtalo de nuevo.")
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
                _messages.send("Portada eliminada")
            } else {
                _messages.send("No se pudo eliminar la portada")
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
            _messages.send(if (removed == 1) "Portada eliminada" else "$removed portadas eliminadas")
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

    fun resetSettings() {
        viewModelScope.launch {
            settingsRepository.resetToDefaults()
            recalculateRequests.emit(settings.value.ratingConfig)
        }
    }
}
