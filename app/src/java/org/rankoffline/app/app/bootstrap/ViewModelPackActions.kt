package org.rankoffline.app

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

fun RankOfflineViewModel.updateLastTab(value: AppTab) = preferences.lastTab(value)


fun RankOfflineViewModel.updateActivePack(value: PackId) = packController.choosePack(value)


fun RankOfflineViewModel.selectAllPacks() = packController.chooseAll()


fun RankOfflineViewModel.refreshAvailablePacks() = packController.discoverAvailable()


fun RankOfflineViewModel.downloadPack(id: PackId) = packController.install(id)


fun RankOfflineViewModel.recoverPack(id: PackId) = packController.recover(id)


fun RankOfflineViewModel.cancelInitialCoverDownload(id: PackId) = packController.cancelInitialCovers(id)


fun RankOfflineViewModel.uninstallPackKeepData(id: PackId) = packController.uninstallKeepPersonal(id)


fun RankOfflineViewModel.purgePack(id: PackId) = packController.purge(id)


fun RankOfflineViewModel.observePackPersonalStorage(id: PackId) = packController.personalStorage(id)


fun RankOfflineViewModel.refreshPackStatistics(
    id: PackId,
    fields: Set<PackSummaryField> = setOf(PackSummaryField.INSTALLED_BYTES, PackSummaryField.COVER_BYTES)
) =
    viewModelScope.launch { try { packController.statistics(id, fields) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { reportError(failure) } }

// Catalog and rankings: first 200 rows, then 50 per request; no eager other-tab queries.
