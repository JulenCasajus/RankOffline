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
    data class AllSearchKey(val manifests: List<PackManifest>, val state: SearchUiState,
                            val merges: Map<PackId, Boolean>, val revision: Long)
    data class AllRankingKey(val manifests: List<PackManifest>, val state: RankingUiState,
                             val merges: Map<PackId, Boolean>, val revision: Long)
    data class AllSearchSnapshot(val key: AllSearchKey, val page: SearchPageState,
                                 val cursor: AllCatalogCursor)
    data class AllRankingSnapshot(val key: AllRankingKey, val page: CatalogUiController.RankingPage,
                                  val cursor: AllRankingCursor)

    private class Slot(
        var search: SearchSnapshot? = null,
        var ranking: RankingSnapshot? = null,
        val searchByMerge: MutableMap<Boolean, SearchSnapshot> = mutableMapOf(),
        val rankingByMerge: MutableMap<Boolean, RankingSnapshot> = mutableMapOf(),
        var allSearch: AllSearchSnapshot? = null,
        var allRanking: AllRankingSnapshot? = null,
        val allSearchByMerge: MutableMap<Boolean, AllSearchSnapshot> = mutableMapOf(),
        val allRankingByMerge: MutableMap<Boolean, AllRankingSnapshot> = mutableMapOf(),
        val searchScroll: LazyListState = LazyListState(),
        val rankingScroll: LazyListState = LazyListState()
    )

    private val slots = LinkedHashMap<ViewingScope, Slot>(capacity, 0.75f, true)
    private val _search = MutableStateFlow<Map<PackId, SearchSnapshot>>(emptyMap())
    val search: StateFlow<Map<PackId, SearchSnapshot>> = _search
    private val _ranking = MutableStateFlow<Map<PackId, RankingSnapshot>>(emptyMap())
    val ranking: StateFlow<Map<PackId, RankingSnapshot>> = _ranking
    private val _allSearch = MutableStateFlow<AllSearchSnapshot?>(null)
    val allSearch: StateFlow<AllSearchSnapshot?> = _allSearch
    private val _allRanking = MutableStateFlow<AllRankingSnapshot?>(null)
    val allRanking: StateFlow<AllRankingSnapshot?> = _allRanking
    val hasAllView: Boolean get() = synchronized(this) { ViewingScope.All in slots }

    @Synchronized fun touch(scope: ViewingScope) {
        if (slots[scope] == null) slots[scope] = Slot()
        if (slots.size > capacity) {
            val eldest = slots.keys.first()
            slots.remove(eldest)
            if (eldest is ViewingScope.Pack) {
                _search.value = _search.value - eldest.packId
                _ranking.value = _ranking.value - eldest.packId
            } else {
                _allSearch.value = null
                _allRanking.value = null
            }
        }
    }

    @Synchronized fun searchScroll(scope: ViewingScope): LazyListState {
        touch(scope)
        return slots.getValue(scope).searchScroll
    }

    @Synchronized fun rankingScroll(scope: ViewingScope): LazyListState {
        touch(scope)
        return slots.getValue(scope).rankingScroll
    }

    @Synchronized fun findSearch(id: PackId, key: SearchKey): SearchSnapshot? =
        slots[ViewingScope.Pack(id)]?.searchByMerge?.get(key.merge)?.takeIf { it.key == key }

    @Synchronized fun findRanking(id: PackId, key: RankingKey): RankingSnapshot? =
        slots[ViewingScope.Pack(id)]?.rankingByMerge?.get(key.merge)?.takeIf { it.key == key }

    @Synchronized fun findAllSearch(key: AllSearchKey): AllSearchSnapshot? =
        slots[ViewingScope.All]?.allSearchByMerge?.get(key.merges.values.any { it })?.takeIf { it.key == key }

    @Synchronized fun findAllRanking(key: AllRankingKey): AllRankingSnapshot? =
        slots[ViewingScope.All]?.allRankingByMerge?.get(key.merges.values.any { it })?.takeIf { it.key == key }

    @Synchronized fun putSearch(id: PackId, value: SearchSnapshot) {
        val scope = ViewingScope.Pack(id)
        touch(scope)
        val slot = slots.getValue(scope)
        slot.search = value.takeIf { it.page.items.size <= MAX_RETAINED_ITEMS }
        slot.search?.let { slot.searchByMerge[value.key.merge] = it }
            ?: slot.searchByMerge.remove(value.key.merge)
        _search.value = slots.mapNotNull { (key, slot) ->
            (key as? ViewingScope.Pack)?.packId?.let { id -> slot.search?.let { id to it } }
        }.toMap()
    }

    @Synchronized fun putRanking(id: PackId, value: RankingSnapshot) {
        val scope = ViewingScope.Pack(id)
        touch(scope)
        val slot = slots.getValue(scope)
        slot.ranking = value.takeIf { it.page.result.items.size <= MAX_RETAINED_ITEMS }
        slot.ranking?.let { slot.rankingByMerge[value.key.merge] = it }
            ?: slot.rankingByMerge.remove(value.key.merge)
        _ranking.value = slots.mapNotNull { (key, slot) ->
            (key as? ViewingScope.Pack)?.packId?.let { id -> slot.ranking?.let { id to it } }
        }.toMap()
    }

    @Synchronized fun putAllSearch(value: AllSearchSnapshot) {
        touch(ViewingScope.All)
        val slot = slots.getValue(ViewingScope.All)
        slot.allSearch = value.takeIf { it.page.items.size <= MAX_RETAINED_ITEMS }
        val merge = value.key.merges.values.any { it }
        slot.allSearch?.let { slot.allSearchByMerge[merge] = it }
            ?: slot.allSearchByMerge.remove(merge)
        _allSearch.value = slot.allSearch
    }

    @Synchronized fun putAllRanking(value: AllRankingSnapshot) {
        touch(ViewingScope.All)
        val slot = slots.getValue(ViewingScope.All)
        slot.allRanking = value.takeIf {
            it.page.result.items.size <= MAX_RETAINED_ITEMS
        }
        val merge = value.key.merges.values.any { it }
        slot.allRanking?.let { slot.allRankingByMerge[merge] = it }
            ?: slot.allRankingByMerge.remove(merge)
        _allRanking.value = slot.allRanking
    }

    @Synchronized fun remove(id: PackId) {
        slots.remove(ViewingScope.Pack(id))
        _search.value = _search.value - id
        _ranking.value = _ranking.value - id
        slots[ViewingScope.All]?.allSearch = null
        slots[ViewingScope.All]?.allRanking = null
        slots[ViewingScope.All]?.allSearchByMerge?.clear()
        slots[ViewingScope.All]?.allRankingByMerge?.clear()
        _allSearch.value = null
        _allRanking.value = null
    }

    @Synchronized fun recentIds(): List<PackId> = slots.keys.toList().asReversed()
        .mapNotNull { (it as? ViewingScope.Pack)?.packId }

    private companion object { const val MAX_RETAINED_ITEMS = 1_200 }
}
