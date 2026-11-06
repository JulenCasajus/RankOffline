package org.rankoffline.app

import android.app.Application
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Thin public API for Compose. Single source of truth: repositories & PackManager.
 * Controllers contain UI orchestration only, not new persistence or pack lifecycle logic.
 */
class RankOfflineViewModel(application: Application) : AndroidViewModel(application) {
    internal val services = RankOfflineServices.get(application)
    internal val settingsRepository = services.settingsRepository
    val settings: StateFlow<AppSettingsState> = settingsRepository.state
    internal val revisions = MediaRevisionTracker()
    internal val selection = MutableStateFlow<ViewingScope>(ViewingScope.All)
    val viewingScope: StateFlow<ViewingScope> = selection.asStateFlow()
    internal val messageChannel = Channel<UserMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    internal val error = MutableStateFlow<UserMessage?>(null)
    val errorMessage: StateFlow<UserMessage?> = error.asStateFlow()

    /** This is deliberately UI-only; no cover scan/bitmap decode is triggered on construction. */
    val coverRepository = CoverBitmapCache()
    internal val catalog = CatalogUiController(viewModelScope, services.mediaRepository,
        services.packManager, settingsRepository, viewingScope, revisions)
    private val allSearchScroll = LazyListState()
    private val allRankingScroll = LazyListState()

    fun searchListState(scope: ViewingScope): LazyListState = when (scope) {
        ViewingScope.All -> allSearchScroll
        is ViewingScope.Pack -> catalog.session.searchScroll(scope.packId)
    }

    fun rankingListState(scope: ViewingScope): LazyListState = when (scope) {
        ViewingScope.All -> allRankingScroll
        is ViewingScope.Pack -> catalog.session.rankingScroll(scope.packId)
    }

    fun preloadRecentCatalogs() = catalog.preloadRecentPacks()
    internal val covers = CoverUiController(viewModelScope, services, coverRepository, ::reportError)
    internal val packController = PackUiController(application, viewModelScope, services, selection,
        onSelected = catalog::preparePack, beforeDelete = covers::cancelAndJoin,
        onChanged = { id -> catalog.removePack(id); covers.invalidate(id) },
        onError = ::reportError,
        onDiscoveryError = { reportError(it, R.string.pack_source_refresh_failed) })
    internal val ratingController = RatingUiController(viewModelScope, services.ratingRepository,
        services.groupRepository, settings, onChanged = catalog::ratingChanged,
        onError = { reportError(it, R.string.message_rating_operation_failed) })
    internal val preferences = PreferencesUiController(viewModelScope, settingsRepository,
        services.ratingRepository, onPackChanged = revisions::ratingsChanged, onError = ::reportError)
    internal val backups = PersonalBackupUiController(application, viewModelScope,
        backup = { services.personalDataBackupManager },
        onRestored = { catalog.refreshRatings(services.packManager.states.value.keys) },
        onError = { reportError(it, R.string.personal_backup_failed) })
    internal val rankingShare = RankingShareUiController(application, viewModelScope,
        services, { reportError(it, R.string.ranking_share_failed) })

    val ready = packController.ready
    val packStates = packController.states
    val discoveringPacks = packController.discoveryInProgress
    val packInstallProgress = packController.progress
    val packDownloadProgress = packController.aggregateProgress
    val filterOptions = catalog.filterOptions
    val searchPageState = catalog.searchPageState
    internal val rankingPage = catalog.rankingPage
    val activeSearchState = catalog.activeSearchState
    val activeRankingState = catalog.activeRankingState
    val coverDownloadProgress by lazy { covers.progress }
    val coverProgressByPack by lazy { covers.progressByPack }
    val ratingEditor = ratingController.editor
    val groupDetail = ratingController.groupDetail
    val backupPreview = backups.preview
    val personalDataTransfer = backups.transfer
    val pendingPersonalDataShare = backups.pendingShare
    internal val rankingShareData = rankingShare.data
    internal val rankingShareExport = rankingShare.export
    internal val pendingRankingShare = rankingShare.pendingShare

    init {
        // No network source discovery, legacy migrations, full ranking or initial covers here.
        packController.bootstrap()
    }

    internal suspend fun reportError(failure: Throwable, resource: Int = R.string.error_initialize_local_data) {
        Log.e("RankOfflineUI", "ViewModel operation failed", failure)
        val userMessage = UserMessage.Text(resource)
        error.value = userMessage
        messageChannel.send(userMessage)
    }

    // Navigation and pack management
}
