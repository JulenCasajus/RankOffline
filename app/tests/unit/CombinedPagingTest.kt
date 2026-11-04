package org.rankoffline.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedPagingTest {
    @Test fun popularityRoundRobinBalancesPacksWithoutLosingIdentity() = runBlocking {
        val ids = listOf(PackId(1), PackId(2), PackId(3))
        val data = ids.associateWith { id -> (1..3).map { "${id.value}:$it" } }
        val page = readCombinedPage(ids, 7, CombinedPageCursor(), true, null) { id, offset, count ->
            data.getValue(id).drop(offset).take(count)
        }
        assertEquals(listOf("1:1", "2:1", "3:1", "1:2", "2:2", "3:2", "1:3"), page.entries)
    }

    @Test fun popularityCursorDoesNotSkipOrDuplicateAcrossPages() = runBlocking {
        val ids = listOf(PackId(1), PackId(2))
        val data = ids.associateWith { id -> (1..5).map { "${id.value}:$it" } }
        suspend fun page(cursor: CombinedPageCursor) = readCombinedPage(ids, 3, cursor, true, null) {
                id, offset, count -> data.getValue(id).drop(offset).take(count)
        }
        val first = page(CombinedPageCursor())
        val second = page(first.nextCursor)
        val third = page(second.nextCursor)
        val values = first.entries + second.entries + third.entries
        assertEquals(values.size, values.distinct().size)
        assertEquals(listOf("1:1", "2:1", "1:2", "2:2", "1:3", "2:3", "1:4", "2:4", "1:5"), values)
    }

    @Test fun orderedMergeConsumesOnlyTheWinningPackCandidate() = runBlocking {
        val one = PackId(1); val two = PackId(2)
        val data = mapOf(one to listOf("a", "d"), two to listOf("b", "c"))
        val first = readCombinedPage(listOf(one, two), 3, CombinedPageCursor(), false,
            compareBy<String> { it }) { id, offset, count -> data.getValue(id).drop(offset).take(count) }
        val second = readCombinedPage(listOf(one, two), 3, first.nextCursor, false,
            compareBy<String> { it }) { id, offset, count -> data.getValue(id).drop(offset).take(count) }
        assertEquals(listOf("a", "b", "c"), first.entries)
        assertEquals(listOf("d"), second.entries)
    }

    @Test fun emptyAndShortPacksTerminateCoherently() = runBlocking {
        val page = readCombinedPage(listOf(PackId(1), PackId(2)), 50,
            CombinedPageCursor(), true, null) { id, _, _ ->
            if (id == PackId(1)) listOf("only") else emptyList()
        }
        assertEquals(listOf("only"), page.entries)
        assertFalse(page.hasMore)
    }

    @Test fun invalidLimitsAndDuplicatePacksAreRejected() = runBlocking {
        val fetch: suspend (PackId, Int, Int) -> List<String> = { _, _, _ -> emptyList() }
        assertTrue(runCatching {
            readCombinedPage(listOf(PackId(1)), 0, CombinedPageCursor(), true, null, fetch)
        }.isFailure)
        assertTrue(runCatching {
            readCombinedPage(listOf(PackId(1), PackId(1)), 1, CombinedPageCursor(), true, null, fetch)
        }.isFailure)
    }

    @Test fun sqliteNoCaseOrderingMatchesAsciiNoCaseContract() {
        assertEquals(0, sqliteNoCaseCompare("TITLE", "title"))
        assertTrue(sqliteNoCaseCompare("alpha", "beta") < 0)
        assertTrue(sqliteNoCaseCompare("é", "Z") > 0)
    }
}
