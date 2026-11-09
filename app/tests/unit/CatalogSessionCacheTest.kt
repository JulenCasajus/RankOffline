package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class CatalogSessionCacheTest {
    @Test fun lruKeepsFiveUniquePacksAndEvictsLeastRecentlyUsed() {
        val cache = CatalogSessionCache()
        (1L..5L).forEach { cache.touch(ViewingScope.Pack(PackId(it))) }
        cache.touch(ViewingScope.Pack(PackId(2)))
        assertEquals(listOf(2L, 5L, 4L, 3L, 1L), cache.recentIds().map { it.value })
        cache.touch(ViewingScope.Pack(PackId(6)))
        assertEquals(listOf(6L, 2L, 5L, 4L, 3L), cache.recentIds().map { it.value })
    }

    @Test fun searchAndRankingPositionsAreDistinctAndSurviveRepeatedAccess() {
        val cache = CatalogSessionCache()
        val id = PackId(12)
        val search = cache.searchScroll(ViewingScope.Pack(id))
        val ranking = cache.rankingScroll(ViewingScope.Pack(id))
        assertNotSame(search, ranking)
        assertSame(search, cache.searchScroll(ViewingScope.Pack(id)))
        assertSame(ranking, cache.rankingScroll(ViewingScope.Pack(id)))
    }

    @Test fun allOccupiesOneOfFiveSlotsAndKeepsSeparateScrollPositions() {
        val cache = CatalogSessionCache()
        val allSearch = cache.searchScroll(ViewingScope.All)
        val allRanking = cache.rankingScroll(ViewingScope.All)
        assertNotSame(allSearch, allRanking)
        (1L..4L).forEach { cache.touch(ViewingScope.Pack(PackId(it))) }
        assertSame(allSearch, cache.searchScroll(ViewingScope.All))
        (5L..9L).forEach { cache.touch(ViewingScope.Pack(PackId(it))) }
        assertNotSame(allSearch, cache.searchScroll(ViewingScope.All))
    }

    @Test fun allPageKeepsCursorAndIsEvictedWithItsView() {
        val cache = CatalogSessionCache()
        val manifest = TestFixtures.manifest(21)
        val key = CatalogSessionCache.AllSearchKey(listOf(manifest), SearchUiState(),
            mapOf(manifest.packId to false), 0L)
        val cursor = AllCatalogCursor(offsets = mapOf(manifest.packId to 200))
        cache.putAllSearch(CatalogSessionCache.AllSearchSnapshot(key,
            SearchPageState(ViewingScope.All, loadingInitial = false, hasMore = true), cursor))
        assertEquals(200, cache.allSearch.value?.cursor?.offsets?.get(manifest.packId))
        (1L..5L).forEach { cache.touch(ViewingScope.Pack(PackId(it))) }
        assertEquals(null, cache.allSearch.value)
    }

    @Test fun recentIdsPersistOnlyFiveDistinctPhysicalPackIds() {
        val previous = (1L..5L).map(::PackId)
        assertEquals(listOf(3L, 1L, 2L, 4L, 5L),
            recentPackOrder(PackId(3), previous).map { it.value })
        assertEquals(listOf(6L, 1L, 2L, 3L, 4L),
            recentPackOrder(PackId(6), previous).map { it.value })
    }

    @Test fun cachedPageRetainsPaginationAndRejectsChangedRequest() {
        val cache = CatalogSessionCache()
        val id = PackId(11)
        val manifest = TestFixtures.manifest(11)
        val key = CatalogSessionCache.SearchKey(manifest, SearchUiState(), false, 0)
        val page = SearchPageState(ViewingScope.Pack(id), loadingInitial = false, hasMore = true)
        val offset = SearchPageAccumulator<MediaListEntry>(nextOffset = 250)
        cache.putSearch(id, CatalogSessionCache.SearchSnapshot(key, page, offset))
        assertEquals(250, cache.findSearch(id, key)?.accumulator?.nextOffset)
        assertEquals(null, cache.findSearch(id, key.copy(revision = 1)))
        (12L..16L).forEach { cache.touch(ViewingScope.Pack(PackId(it))) }
        assertEquals(null, cache.findSearch(id, key))
    }

    @Test fun filterMatchModeInvalidatesOnlyThatSearchRequest() {
        val cache = CatalogSessionCache()
        val id = PackId(11)
        val manifest = TestFixtures.manifest(11)
        val values = setOf("drama", "comedy")
        val any = CatalogSessionCache.SearchKey(manifest, SearchUiState(filters = CatalogFilters(mapOf(
            "genre" to CatalogFilterValue.Choices(values)
        ))), false, 0)
        val all = any.copy(state = SearchUiState(filters = CatalogFilters(mapOf(
            "genre" to CatalogFilterValue.Choices(values, matchAll = true)
        ))))
        val page = SearchPageState(ViewingScope.Pack(id), loadingInitial = false)
        cache.putSearch(id, CatalogSessionCache.SearchSnapshot(any, page,
            SearchPageAccumulator<MediaListEntry>(nextOffset = 200)))
        assertSame(page, cache.findSearch(id, any)?.page)
        assertEquals(null, cache.findSearch(id, all))
    }

    @Test fun mergeVariantsRemainAvailableUntilTheirQueryChanges() {
        val cache = CatalogSessionCache()
        val id = PackId(11)
        val manifest = TestFixtures.manifest(11)
        val off = CatalogSessionCache.SearchKey(manifest, SearchUiState(), false, 0)
        val on = off.copy(merge = true)
        val page = SearchPageState(ViewingScope.Pack(id), loadingInitial = false)
        val accumulator = SearchPageAccumulator<MediaListEntry>(nextOffset = 200)
        cache.putSearch(id, CatalogSessionCache.SearchSnapshot(off, page, accumulator))
        cache.putSearch(id, CatalogSessionCache.SearchSnapshot(on, page, accumulator))
        assertSame(page, cache.findSearch(id, off)?.page)
        assertSame(page, cache.findSearch(id, on)?.page)
        assertEquals(null, cache.findSearch(id, on.copy(state = SearchUiState(query = "changed"))))
    }

    @Test fun allMergeVariantsRetainTheirOwnCursor() {
        val cache = CatalogSessionCache()
        val manifest = TestFixtures.manifest(21)
        val off = CatalogSessionCache.AllSearchKey(listOf(manifest), SearchUiState(),
            mapOf(manifest.packId to false), 0L)
        val on = off.copy(merges = mapOf(manifest.packId to true))
        val page = SearchPageState(ViewingScope.All, loadingInitial = false)
        val offCursor = AllCatalogCursor(offsets = mapOf(manifest.packId to 200))
        val onCursor = AllCatalogCursor(offsets = mapOf(manifest.packId to 250))
        cache.putAllSearch(CatalogSessionCache.AllSearchSnapshot(off, page, offCursor))
        cache.putAllSearch(CatalogSessionCache.AllSearchSnapshot(on, page, onCursor))
        assertEquals(200, cache.findAllSearch(off)?.cursor?.offsets?.get(manifest.packId))
        assertEquals(250, cache.findAllSearch(on)?.cursor?.offsets?.get(manifest.packId))
    }
}
