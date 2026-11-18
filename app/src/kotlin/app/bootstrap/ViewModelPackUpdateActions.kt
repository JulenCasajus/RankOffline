package org.rankoffline.app

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

fun RankOfflineViewModel.updatePack(id: PackId) = packController.update(id)

fun RankOfflineViewModel.postponePackUpdates(targets: Collection<Pair<PackId, String>>) =
    viewModelScope.launch { settingsRepository.postponePackUpdates(targets) }

/** A committed catalog revision invalidates only that pack's retained pages and bitmaps. */
internal fun RankOfflineViewModel.observePackReplacements() = viewModelScope.launch {
    val lastReady = mutableMapOf<PackId, PackManifest>()
    packStates.map { states -> states.mapNotNull { (id, state) ->
        state.manifest?.takeIf { state.available }?.let { id to it }
    }.toMap() }.distinctUntilChanged().collect { current ->
        current.forEach { (id, manifest) ->
            val old = lastReady.put(id, manifest)
            if (old != null && old != manifest) {
                catalog.removePack(id)
                covers.invalidate(id)
            }
        }
    }
}
