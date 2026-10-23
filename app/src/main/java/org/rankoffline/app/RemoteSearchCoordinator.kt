package org.rankoffline.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Owns explicit, user-triggered remote discovery independently from local catalog pagination. */
internal class RemoteSearchCoordinator(
    private val scope: CoroutineScope,
    private val repository: GenericMediaRepository,
    private val onItemsImported: suspend (MediaType) -> Unit
) {
    private val _states = MutableStateFlow(MediaType.entries.associateWith { RemoteSearchUiState() })
    val states: StateFlow<Map<MediaType, RemoteSearchUiState>> = _states.asStateFlow()
    private var job: Job? = null

    fun queryChanged(mediaType: MediaType, query: String) {
        job?.cancel()
        _states.value = _states.value + (mediaType to RemoteSearchUiState(query = query))
    }

    fun cancel() {
        job?.cancel()
    }

    fun search(mediaType: MediaType, query: String) {
        if (query.isNotBlank()) load(mediaType, query.trim(), reset = true)
    }

    fun loadMore(mediaType: MediaType) {
        val state = _states.value.getValue(mediaType)
        if (state.hasMore && state.status != RemoteSearchStatus.LOADING) {
            load(mediaType, state.query, reset = false)
        }
    }

    private fun load(mediaType: MediaType, query: String, reset: Boolean) {
        val provider = RemoteSearchProviders.forMediaType(mediaType) ?: return
        if (job?.isActive == true) return
        val previous = _states.value.getValue(mediaType)
        val offset = if (reset) 0 else previous.nextOffset
        _states.value = _states.value + (mediaType to previous.copy(
            query = query,
            status = RemoteSearchStatus.LOADING,
            items = if (reset) emptyList() else previous.items
        ))
        job = scope.launch {
            try {
                val page = provider.search(query, offset)
                val imported = page.records.mapNotNull { record ->
                    repository.upsertDiscoveredItem(record)
                    repository.item(record.id)?.let { MediaListEntry.Item(it).toAnimeDisplay() }
                }
                val current = _states.value.getValue(mediaType)
                if (current.query != query) return@launch
                val items = ((if (reset) emptyList() else current.items) + imported).distinctBy(Anime::id)
                _states.value = _states.value + (mediaType to current.copy(
                    status = if (items.isEmpty()) RemoteSearchStatus.EMPTY else RemoteSearchStatus.RESULTS,
                    items = items,
                    nextOffset = page.nextOffset,
                    hasMore = page.hasMore
                ))
                onItemsImported(mediaType)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val current = _states.value.getValue(mediaType)
                if (current.query == query) {
                    _states.value = _states.value +
                        (mediaType to current.copy(status = RemoteSearchStatus.ERROR))
                }
            }
        }
    }
}
