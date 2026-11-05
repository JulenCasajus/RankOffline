package org.rankoffline.app

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.Locale

/** Sharing is explicitly user-triggered. No ranking materialization on cold start. */
internal class RankingShareUiController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val services: RankOfflineServices,
    private val onError: suspend (Throwable) -> Unit
) {
    private val dataState = MutableStateFlow(RankingShareData(ViewingScope.All, loading = false))
    val data: StateFlow<RankingShareData> = dataState.asStateFlow()
    private val exportState = MutableStateFlow(RankingShareExportState())
    val export: StateFlow<RankingShareExportState> = exportState.asStateFlow()
    private val pendingShareState = MutableStateFlow<Uri?>(null)
    val pendingShare: StateFlow<Uri?> = pendingShareState.asStateFlow()
    private var loading: Job? = null

    fun prepare(selected: ViewingScope) {
        loading?.cancel()
        dataState.value = RankingShareData(selected)
        loading = scope.launch {
            try {
                val states = services.packManager.states.value.values
                if (selected == ViewingScope.All && states.any { it.installed && !it.available }) {
                    dataState.value = RankingShareData(selected, loading = false, failed = true)
                    return@launch
                }
                val available = states.filter { it.available }
                val manifests = when (selected) {
                    ViewingScope.All -> available.mapNotNull { it.manifest }.sortedBy { it.packId.value }
                    is ViewingScope.Pack -> available.filter { it.packId == selected.packId }.mapNotNull { it.manifest }
                }
                if (selected == ViewingScope.All) {
                    services.settingsRepository.ensurePackSettingsLoaded(manifests.map { it.packId })
                }
                val filter = RankingUiState(sortOrder = RankingSortOrder.FINAL_SCORE)
                val entries = manifests.flatMap { manifest ->
                    val preferences = services.settingsRepository.state.value
                    val enabled = if (selected == ViewingScope.All) preferences.allViewSettings.mergeEnabled
                        else preferences.mediaSettings[manifest.packId]?.mergeEnabled == true
                    services.mediaRepository.ranking(manifest, filter,
                        enabled && manifest.capabilities.merge, limit = 10)
                }.sortedWith(rankingComparator(filter)).filter { it.finalScore != null }.take(10)
                val currentSettings = services.settingsRepository.state.value
                val imageKeys = entries.associate { it.key to it.displayCoverKey(currentSettings) }
                // A GROUP resolves its dedicated cover first, then a valid representative file.
                // Do not duplicate physical files to prepare a PNG.
                val paths = imageKeys.values.distinct().associateWith { key ->
                    services.mediaCoverRepository.observeCover(key).first().localPath
                }
                dataState.value = RankingShareData(selected, entries.map { entry ->
                    RankingShareItem(entry.packId, entry.id, entry.title,
                        formatScoreForDisplay(requireNotNull(entry.finalScore), Locale.getDefault()),
                        paths[imageKeys.getValue(entry.key)])
                }, topSevenBackground = services.rankingShareExporter.backgroundFor(selected, RankingShareTemplate.TOP_SEVEN),
                    topTenBackground = services.rankingShareExporter.backgroundFor(selected, RankingShareTemplate.TOP_TEN),
                    loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Log.e("RankOfflineShare", "Failed to prepare sharing", failure)
                dataState.value = RankingShareData(selected, loading = false, failed = true)
                onError(failure)
            }
        }
    }
    fun render(request: RankingShareRenderRequest) {
        if (exportState.value.rendering || request.items.isEmpty()) return
        exportState.value = RankingShareExportState(rendering = true)
        scope.launch {
            try {
                val file = services.rankingShareExporter.export(request)
                pendingShareState.value = FileProvider.getUriForFile(context,
                    "${context.packageName}.personal-backups", file)
                exportState.value = RankingShareExportState(uriReady = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { onError(failure); exportState.value = RankingShareExportState() }
        }
    }
    fun handled() { pendingShareState.value = null; exportState.value = RankingShareExportState() }
}
