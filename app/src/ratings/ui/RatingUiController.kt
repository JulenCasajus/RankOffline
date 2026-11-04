package org.rankoffline.app

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A single editor and mutation path for items AND independently rated groups. */
internal class RatingUiController(
    private val scope: CoroutineScope,
    private val ratings: RatingRepository,
    private val groups: GroupRepository,
    private val settings: StateFlow<AppSettingsState>,
    private val onChanged: (CatalogEntryKey, RatingDraft) -> Unit,
    private val onError: suspend (Throwable) -> Unit
) {
    private val editorState = MutableStateFlow<RatingEditorState?>(null)
    val editor: StateFlow<RatingEditorState?> = editorState.asStateFlow()
    private val detailState = MutableStateFlow<MediaGroupDetail?>(null)
    val groupDetail: StateFlow<MediaGroupDetail?> = detailState.asStateFlow()
    private var openJob: Job? = null

    fun open(entry: MediaListEntry, fromMembers: Boolean = false) {
        openJob?.cancel()
        editorState.value = null
        openJob = scope.launch {
            try {
                val draft = ratings.loadRating(entry.key, fromMembers)
                editorState.value = RatingEditorState(entry, draft)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { onError(failure) }
        }
    }
    fun openGroup(key: CatalogEntryKey) = scope.launch {
        try { detailState.value = groups.detail(key) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
    }
    fun closeGroup() { detailState.value = null }
    fun closeEditor() { openJob?.cancel(); openJob = null; editorState.value = null }

    fun saveSimple(draft: RatingDraft, quality: Double, final: Double) = mutate("simple") { key ->
        ratings.saveSimpleRating(key, draft, quality, final)
    }
    fun saveElaborate(draft: RatingDraft, profileId: String, override: Double? = null) =
        mutate("elaborate") { key ->
            val config = requireNotNull(settings.value.mediaSettings[key.packId]) {
                "Pack settings are not loaded"
            }
            val profile = config.ratingProfiles.resolveProfile(profileId)
            ratings.saveElaborateRating(key, draft, config.ratingConfig.withProfile(profile),
                override, profile.id)
        }
    fun saveMetadata(notes: String, status: String) = mutate("metadata") { key ->
        ratings.saveMetadata(key, notes, status)
    }
    fun delete() = mutate("delete") { key -> ratings.deleteRating(key) }

    private fun mutate(label: String, action: suspend (CatalogEntryKey) -> Unit) {
        val target = editorState.value ?: return
        scope.launch {
            try {
                action(target.key)
                // Reload from the single Room source of truth; no optimistic duplicate scores.
                val persisted = ratings.loadRating(target.key)
                if (editorState.value?.key == target.key) {
                    editorState.value = target.copy(draft = persisted)
                }
                onChanged(target.key, persisted)
                if (detailState.value?.group?.key == target.key) detailState.value = groups.detail(target.key)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Log.e("RankOfflineRating", "Rating $label failed", failure)
                onError(failure)
            }
        }
    }
}
