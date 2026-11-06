package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class CatalogSessionCacheTest {
    @Test fun lruKeepsFiveUniquePacksAndEvictsLeastRecentlyUsed() {
        val cache = CatalogSessionCache()
        (1L..5L).forEach { cache.touch(PackId(it)) }
        cache.touch(PackId(2))
        assertEquals(listOf(2L, 5L, 4L, 3L, 1L), cache.recentIds().map { it.value })
        cache.touch(PackId(6))
        assertEquals(listOf(6L, 2L, 5L, 4L, 3L), cache.recentIds().map { it.value })
    }

    @Test fun searchAndRankingPositionsAreDistinctAndSurviveRepeatedAccess() {
        val cache = CatalogSessionCache()
        val id = PackId(12)
        val search = cache.searchScroll(id)
        val ranking = cache.rankingScroll(id)
        assertNotSame(search, ranking)
        assertSame(search, cache.searchScroll(id))
        assertSame(ranking, cache.rankingScroll(id))
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
        (12L..16L).forEach { cache.touch(PackId(it)) }
        assertEquals(null, cache.findSearch(id, key))
    }
}
