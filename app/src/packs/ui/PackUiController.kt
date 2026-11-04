package org.rankoffline.app

import android.content.Context
import android.util.Log
import java.io.File
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One UI-facing boundary for installed packs and durable WorkManager operations. */
internal class PackUiController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val services: RankOfflineServices,
    private val selection: MutableStateFlow<ViewingScope>,
    private val onSelected: (PackId, CatalogSortOrder) -> Unit,
    private val beforeDelete: suspend (PackId) -> Unit,
    private val onChanged: (PackId) -> Unit,
    private val onError: suspend (Throwable) -> Unit,
    private val onDiscoveryError: suspend (Throwable) -> Unit
) {
    val states: StateFlow<Map<PackId, PackState>> = services.packManager.states
    val progress: StateFlow<Map<PackId, PackInstallProgress>> = services.packManager.installProgress
    val aggregateProgress: StateFlow<PackAggregateProgress?> = services.packManager.downloadProgress
    val viewingScope: StateFlow<ViewingScope> = selection.asStateFlow()
    private val bootstrapped = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = bootstrapped.asStateFlow()
    private val discovering = MutableStateFlow(false)
    val discoveryInProgress: StateFlow<Boolean> = discovering.asStateFlow()
    private var discoveryJob: Job? = null

    fun bootstrap(afterReady: suspend () -> Unit = {}) = scope.launch {
        try {
            // No eager full catalogue scan, all-packs preloading or cover downloads.
            services.settingsRepository.loadInitialState()
            // Only inspect the journal filename; construct the backup service if recovery is needed.
            val restoreJournal = File(context.filesDir, "personal-restore-journal.json")
            if (restoreJournal.exists() || File("${restoreJournal.path}.bak").exists()) {
                services.personalDataBackupManager.recoverInterruptedImport()
            }
            val active = services.initializeVisiblePack()
            val savedScope = services.settingsRepository.state.value.lastViewingScope
            when (savedScope) {
                ViewingScope.All -> {
                    services.ensureAllPacksReady()
                    selection.value = ViewingScope.All
                }
                is ViewingScope.Pack -> {
                    if (active != null && active == savedScope.packId) {
                        onSelected(active, services.settingsRepository.state.value.catalogSortOrders[active]
                            ?: CatalogSortOrder.POPULARITY)
                        selection.value = savedScope
                    } else {
                        selection.value = ViewingScope.All
                        services.settingsRepository.updateLastViewingScope(ViewingScope.All)
                        services.ensureAllPacksReady()
                    }
                }
            }
            active?.let { id -> scope.launch {
                runCatching { services.refreshPackStatistics(id,
                    setOf(PackSummaryField.AVAILABLE_COVER_COUNT)) }
            } }
            // WorkManager resumes its own persistent jobs; don't initialize it merely for a screen.
            afterReady()
            // WorkManager survives process death; its terminal failures must also reach Compose.
            scope.launch {
                try { PackWorkScheduler.observePersistentWork(context, services.packManager) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    Log.e("RankOfflineWork", "WorkManager observation failed", failure)
                    onError(failure)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Log.e("RankOfflineStartup", "Initialization failed", failure); onError(failure) }
        finally { bootstrapped.value = true }
    }

    fun choosePack(id: PackId) = scope.launch {
        try {
            if (!services.ensurePackReady(id)) return@launch
            services.settingsRepository.updateActivePack(id)
            onSelected(id, services.settingsRepository.state.value.catalogSortOrders[id]
                ?: CatalogSortOrder.POPULARITY)
            selection.value = ViewingScope.Pack(id)
            services.settingsRepository.updateLastViewingScope(ViewingScope.Pack(id))
            scope.launch { runCatching { services.refreshPackStatistics(id,
                setOf(PackSummaryField.AVAILABLE_COVER_COUNT)) } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
    }

    fun chooseAll() = scope.launch {
        try {
            services.ensureAllPacksReady()
            selection.value = ViewingScope.All
            services.settingsRepository.updateLastViewingScope(ViewingScope.All)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
    }

    fun discoverAvailable() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            discovering.value = true
            try {
                services.refreshAvailablePacks()
                PackWorkScheduler.reconcilePersistentWork(context, services.packManager)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { onDiscoveryError(failure) }
            finally { discovering.value = false }
        }
    }

    fun install(id: PackId) = scope.launch {
        try {
            PackWorkScheduler.enqueue(context, id, PackWorkAction.INSTALL)
            // The WorkManager observer sets QUEUED once the request is persisted.
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
    }

    fun recover(id: PackId) = PackWorkScheduler.enqueue(context, id, PackWorkAction.RECOVER)

    fun cancelInitialCovers(id: PackId) = scope.launch { services.cancelInitialCoverDownloads(id) }

    /** Uninstall retains local rating JSON, DataStore and personal ratings in Room. */
    fun uninstallKeepPersonal(id: PackId) = scope.launch {
        try {
            PackWorkScheduler.cancel(context, id)
            beforeDelete(id)
            if (services.packManager.delete(id)) {
                onChanged(id)
                fallbackAfterDelete(id)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
    }

    /** Purge is explicitly destructive, unlike uninstall; caller must confirm in the UI. */
    fun purge(id: PackId) = scope.launch {
        try {
            PackWorkScheduler.cancel(context, id)
            beforeDelete(id)
            if (!services.packManager.delete(id)) return@launch
            services.database.withTransaction {
                services.database.personalDataDao().deletePersonalData(id.value)
            }
            services.settingsRepository.purgePackSettings(id)
            onChanged(id)
            fallbackAfterDelete(id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
    }

    fun personalStorage(id: PackId) = services.packStorageRepository.observePersonalStorage(id)
    suspend fun statistics(id: PackId, fields: Set<PackSummaryField> = PackSummaryField.entries.toSet()) =
        services.refreshPackStatistics(id, fields)

    private suspend fun fallbackAfterDelete(deleted: PackId) {
        val installed = services.packManager.states.value.values
            .mapNotNull { state -> state.manifest?.takeIf { state.installed } }
        services.settingsRepository.syncInstalledPacks(installed)
        val replacement = services.settingsRepository.state.value.activePackId
        if (replacement != null) services.settingsRepository.updateActivePack(replacement)
        if ((selection.value as? ViewingScope.Pack)?.packId == deleted) {
            if (replacement == null) {
                selection.value = ViewingScope.All
                services.settingsRepository.updateLastViewingScope(ViewingScope.All)
            }
            else choosePack(replacement)
        }
    }
}
