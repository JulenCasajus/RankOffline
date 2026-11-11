package org.rankoffline.app

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cover jobs are never started by startup or merely changing tabs. */
internal class CoverUiController(
    private val scope: CoroutineScope,
    private val services: RankOfflineServices,
    private val bitmapCache: CoverBitmapCache,
    private val onError: suspend (Throwable) -> Unit,
    private val onDownloadFailure: suspend (Int) -> Unit
) {
    private val activeBatches = mutableMapOf<PackId, Job>()
    val progress: StateFlow<CoverDownloadProgress> get() = services.coverDownloadsManager.progress
    val progressByPack: StateFlow<Map<PackId, CoverDownloadProgress>>
        get() = services.coverDownloadsManager.progressByPack

    fun setProgressVisible(packId: PackId, visible: Boolean) =
        services.coverDownloadsManager.setProgressVisible(packId, visible)

    fun download(key: CatalogEntryKey) = scope.launch {
        try {
            when (services.downloadCover(key)) {
                CoverDownloadResult.NotFound -> onDownloadFailure(R.string.message_cover_not_found)
                CoverDownloadResult.NetworkError -> onDownloadFailure(R.string.cover_network_error)
                CoverDownloadResult.TemporaryError -> onDownloadFailure(R.string.cover_temporary_error)
                else -> Unit
            }
            services.refreshPackStatistics(key.packId,
                setOf(PackSummaryField.AVAILABLE_COVER_COUNT))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
    }
    fun downloadTop(packId: PackId, limit: Int) {
        require(limit in 1..5000)
        val previous = activeBatches[packId]
        activeBatches[packId] = scope.launch {
            try {
                previous?.cancelAndJoin()
                services.settingsRepository.ensurePackSettingsLoaded(packId)
                val settings = services.settingsRepository.state.value.mediaSettings[packId]
                services.coverDownloadsManager.downloadTop(packId, limit,
                    mergeEnabled = settings?.mergeEnabled == true)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Log.e("RankOfflineCovers", "Batch failed", failure); onError(failure) }
            finally {
                withContext(NonCancellable) {
                    runCatching { services.refreshPackStatistics(packId,
                        setOf(PackSummaryField.AVAILABLE_COVER_COUNT)) }
                        .onFailure { Log.w("RankOfflineCovers", "Cover count refresh failed", it) }
                }
                // An older cancelled batch must not erase a replacement job.
                if (activeBatches[packId] === currentCoroutineContext().job) {
                    activeBatches.remove(packId)
                }
            }
        }
    }
    fun cancelBatch(packId: PackId) {
        activeBatches.remove(packId)?.cancel()
        // Covers shows progress from both manual batches and the initial WorkManager batch.
        scope.launch {
            try { services.cancelInitialCoverDownloads(packId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { onError(failure) }
        }
    }
    suspend fun cancelAndJoin(packId: PackId) { activeBatches.remove(packId)?.cancelAndJoin() }

    fun delete(key: CatalogEntryKey, finished: (Boolean) -> Unit = {}) = scope.launch {
        var success = false
        try {
            val oldPath = services.mediaCoverRepository.observeCover(key).first().localPath
            success = services.mediaCoverRepository.removeLocalCover(key)
            if (success) services.refreshPackStatistics(key.packId,
                setOf(PackSummaryField.AVAILABLE_COVER_COUNT))
            if (success && oldPath != null) bitmapCache.invalidate(key.coverCacheIdentity(), oldPath)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
        finally {
            finished(success)
        }
    }
    fun clear(packId: PackId) = scope.launch {
        try {
            if (services.mediaCoverRepository.clearLocalCovers(packId)) {
                services.refreshPackStatistics(packId,
                    setOf(PackSummaryField.AVAILABLE_COVER_COUNT))
                bitmapCache.invalidatePack(packId)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
    }
    fun invalidate(packId: PackId) {
        services.mediaCoverRepository.invalidatePackRuntimeState(packId)
        bitmapCache.invalidatePack(packId)
    }
}

private fun CatalogEntryKey.coverCacheIdentity(): String =
    CoverStorageIdentity(packId, id, kind).cacheIdentity()
