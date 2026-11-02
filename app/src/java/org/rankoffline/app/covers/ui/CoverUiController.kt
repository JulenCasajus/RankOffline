package org.rankoffline.app

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Cover jobs are never started by startup or merely changing tabs. */
internal class CoverUiController(
    private val scope: CoroutineScope,
    private val services: RankOfflineServices,
    private val bitmapCache: CoverBitmapCache,
    private val onError: suspend (Throwable) -> Unit
) {
    private val activeBatches = mutableMapOf<PackId, Job>()
    val progress: StateFlow<CoverDownloadProgress> get() = services.coverDownloadsManager.progress
    val progressByPack: StateFlow<Map<PackId, CoverDownloadProgress>>
        get() = services.coverDownloadsManager.progressByPack

    fun download(key: CatalogEntryKey) = scope.launch {
        try {
            val reuse = services.settingsRepository.state.value.mediaSettings[key.packId]
                ?.useGroupCoversForItems == true
            val displayKey = services.mediaCoverRepository.displayedCoverKey(key, reuse)
            services.mediaCoverRepository.downloadCover(displayKey)
            bitmapCache.invalidateAll()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
    }
    fun downloadTop(packId: PackId, limit: Int) {
        require(limit in 1..5000)
        activeBatches[packId]?.cancel()
        activeBatches[packId] = scope.launch {
            try {
                services.settingsRepository.ensurePackSettingsLoaded(packId)
                val settings = services.settingsRepository.state.value.mediaSettings[packId]
                services.coverDownloadsManager.downloadTop(packId, limit,
                    mergeEnabled = settings?.mergeEnabled == true,
                    useGroupCoversForItems = settings?.useGroupCoversForItems == true)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Log.e("RankOfflineCovers", "Batch failed", failure); onError(failure) }
            finally {
                // An older cancelled batch must not erase a replacement job.
                if (activeBatches[packId] === currentCoroutineContext().job) {
                    activeBatches.remove(packId)
                }
            }
        }
    }
    fun cancelBatch(packId: PackId) { activeBatches.remove(packId)?.cancel() }
    suspend fun cancelAndJoin(packId: PackId) { activeBatches.remove(packId)?.cancelAndJoin() }

    fun delete(key: CatalogEntryKey, finished: (Boolean) -> Unit = {}) = scope.launch {
        var success = false
        try {
            val reuse = services.settingsRepository.state.value.mediaSettings[key.packId]
                ?.useGroupCoversForItems == true
            val displayKey = services.mediaCoverRepository.displayedCoverKey(key, reuse)
            success = services.mediaCoverRepository.removeLocalCover(displayKey)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
        finally {
            if (success) bitmapCache.invalidateAll()
            finished(success)
        }
    }
    fun clear(packId: PackId) = scope.launch {
        try {
            if (services.mediaCoverRepository.clearLocalCovers(packId)) bitmapCache.invalidateAll()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
    }
    fun invalidate(packId: PackId) {
        services.mediaCoverRepository.invalidatePackRuntimeState(packId)
        bitmapCache.invalidateAll()
    }
}
