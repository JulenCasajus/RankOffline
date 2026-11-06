package org.rankoffline.app

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Only pages actually visited in this process are retained. No catalog or bitmap is copied. */
internal class CatalogSessionCache(private val capacity: Int = 5) {
    init { require(capacity > 0) }

    data class SearchKey(val manifest: PackManifest, val state: SearchUiState,
                         val merge: Boolean, val revision: Long)
    data class RankingKey(val manifest: PackManifest, val state: RankingUiState,
                          val merge: Boolean, val revision: Long)
    data class SearchSnapshot(val key: SearchKey, val page: SearchPageState,
                              val accumulator: SearchPageAccumulator<MediaListEntry>)
    data class RankingSnapshot(val key: RankingKey, val page: CatalogUiController.RankingPage,
                               val accumulator: SearchPageAccumulator<MediaListEntry>)

    private class Slot(
        var search: SearchSnapshot? = null,
        var ranking: RankingSnapshot? = null,
        val searchScroll: LazyListState = LazyListState(),
        val rankingScroll: LazyListState = LazyListState()
    )

    private val slots = LinkedHashMap<PackId, Slot>(capacity, 0.75f, true)
    private val _search = MutableStateFlow<Map<PackId, SearchSnapshot>>(emptyMap())
    val search: StateFlow<Map<PackId, SearchSnapshot>> = _search
    private val _ranking = MutableStateFlow<Map<PackId, RankingSnapshot>>(emptyMap())
    val ranking: StateFlow<Map<PackId, RankingSnapshot>> = _ranking

    @Synchronized fun touch(id: PackId) {
        if (slots[id] == null) slots[id] = Slot()
        if (slots.size > capacity) {
            val eldest = slots.keys.first()
            slots.remove(eldest)
            _search.value = _search.value - eldest
            _ranking.value = _ranking.value - eldest
        }
    }

    @Synchronized fun searchScroll(id: PackId): LazyListState {
        touch(id)
        return slots.getValue(id).searchScroll
    }

    @Synchronized fun rankingScroll(id: PackId): LazyListState {
        touch(id)
        return slots.getValue(id).rankingScroll
    }

    @Synchronized fun findSearch(id: PackId, key: SearchKey): SearchSnapshot? =
        slots[id]?.search?.takeIf { it.key == key }

    @Synchronized fun findRanking(id: PackId, key: RankingKey): RankingSnapshot? =
        slots[id]?.ranking?.takeIf { it.key == key }

    @Synchronized fun putSearch(id: PackId, value: SearchSnapshot) {
        touch(id)
        slots.getValue(id).search = value.takeIf { it.page.items.size <= MAX_RETAINED_ITEMS }
        _search.value = slots.mapNotNull { (key, slot) -> slot.search?.let { key to it } }.toMap()
    }

    @Synchronized fun putRanking(id: PackId, value: RankingSnapshot) {
        touch(id)
        slots.getValue(id).ranking = value.takeIf { it.page.result.items.size <= MAX_RETAINED_ITEMS }
        _ranking.value = slots.mapNotNull { (key, slot) -> slot.ranking?.let { key to it } }.toMap()
    }

    @Synchronized fun remove(id: PackId) {
        slots.remove(id)
        _search.value = _search.value - id
        _ranking.value = _ranking.value - id
    }

    @Synchronized fun recentIds(): List<PackId> = slots.keys.toList().asReversed()

    private companion object { const val MAX_RETAINED_ITEMS = 1_200 }
}
