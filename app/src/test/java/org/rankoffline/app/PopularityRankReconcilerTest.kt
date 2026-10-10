package org.rankoffline.app

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class PopularityRankReconcilerTest {
    @Test
    fun top100EntrantsAndDisplacedAnimeSwapTheirOldSlots() {
        val previous = listOf(
            rank("A", 93), rank("B", 94), rank("C", 95), rank("D", 96),
            rank("E", 97), rank("F", 98), rank("G", 99), rank("H", 100),
            rank("X", 105), rank("outside", 108), rank("Y", 112)
        )
        val incoming = listOf(
            incoming("X", 93), incoming("A", 94), incoming("Y", 95),
            incoming("B", 96), incoming("C", 97), incoming("E", 98),
            incoming("F", 99), incoming("H", 100)
        )

        val result = PopularityRankReconciler.reconcile(previous, incoming, 100).finalRanks

        assertEquals(93, result.getValue("X"))
        assertEquals(95, result.getValue("Y"))
        assertEquals(105, result.getValue("D"))
        assertEquals(112, result.getValue("G"))
        assertEquals(108, result.getValue("outside"))
    }

    @Test
    fun nullRankEntrantMovesDisplacedAnimeAfterKnownTail() {
        val previous = listOf(rank("A", 1), rank("D", 2), rank("tail", 105))
        val incoming = listOf(incoming("A", 1), incoming("new", 2))

        val result = PopularityRankReconciler.reconcile(previous, incoming, 100).finalRanks

        assertEquals(2, result.getValue("new"))
        assertEquals(106, result.getValue("D"))
    }

    @Test
    fun top5000PlacesExpelledFirstThenPreservesExistingTailOrder() {
        val previous = listOf(
            rank("kept", 1), rank("expelledA", 20), rank("expelledB", 4000),
            rank("tailA", 5008), rank("entrant", 5012), rank("tailB", 5020)
        )
        val incoming = listOf(incoming("kept", 1), incoming("entrant", 2))

        val result = PopularityRankReconciler.reconcile(previous, incoming, 5000).finalRanks

        assertEquals(5001, result.getValue("expelledA"))
        assertEquals(5002, result.getValue("expelledB"))
        assertEquals(5003, result.getValue("tailA"))
        assertEquals(5004, result.getValue("tailB"))
    }

    @Test
    fun everyNonNullRankRemainsUnique() {
        val previous = (1..150).map { rank("old$it", it) } + rank("entrant", 170)
        val incoming = (1..99).map { incoming("old$it", it) } + incoming("entrant", 100)

        val result = PopularityRankReconciler.reconcile(previous, incoming, 100).finalRanks

        assertEquals(result.size, result.values.distinct().size)
    }

    @Test
    fun incompleteApiResponseIsRejectedBeforeReconciliation() {
        val previous = listOf(rank("unchanged", 1))
        val incomplete = listOf(PopularAnimeEntry(1, 1, 10, null))

        try {
            validatePopularAnimeResponse(incomplete, 100)
            fail("Expected incomplete AniList response to be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(listOf(rank("unchanged", 1)), previous)
        }
    }

    @Test
    fun top100UsesExactlyTwoPagesOf50UniqueIdsAndReconciles() = runBlocking {
        val pageRequests = mutableListOf<Pair<Int, Int>>()
        val fetched = fetchCompletePopularAnime(
            count = 100,
            pageSize = 50,
            fetchPage = { page, perPage ->
                pageRequests += page to perPage
                val firstId = (page - 1) * perPage + 1
                (firstId until firstId + perPage).map { id ->
                    PopularAnimeEntry(id.toLong(), id.toLong(), 10_000 - id, null)
                }
            }
        )
        val previous = (1..100).map { rank("anime$it", it) }
        val incoming = fetched.mapIndexed { index, _ -> incoming("anime${index + 1}", index + 1) }
        val reconciled = PopularityRankReconciler.reconcile(previous, incoming, 100).finalRanks

        assertEquals(listOf(1 to 50, 2 to 50), pageRequests)
        assertEquals(100, fetched.size)
        assertEquals(100, fetched.map { it.anilistId }.distinct().size)
        assertEquals((1..100).toList(), reconciled.values.sorted())
    }

    @Test
    fun failedSecondTop100PageKeepsRankingAndNeverStartsCovers() = runBlocking {
        val previous = (1..100).associate { "anime$it" to it }
        var ranking = previous
        var progress = TopUpdateProgress.start(100)
        var rankingApplied = false

        try {
            val fetched = fetchCompletePopularAnime(
                count = 100,
                pageSize = 50,
                fetchPage = { page, perPage ->
                    if (page == 2) throw IOException("temporary second-page failure")
                    (1..perPage).map { id ->
                        PopularAnimeEntry(id.toLong(), id.toLong(), 10_000 - id, null)
                    }
                },
                onPageFetched = { _, _, _, total ->
                    progress = TopUpdateProgress.popularityFetched(progress, total)
                }
            )
            progress = TopUpdateProgress.applyingPopularity(progress)
            ranking = fetched.mapIndexed { index, _ -> "anime${index + 1}" to index + 1 }.toMap()
            rankingApplied = true
            progress = TopUpdateProgress.downloadingCovers(progress, 0, 0, 0)
        } catch (_: IOException) {
            progress = progress.copy(status = CoverPreloadStatus.FAILED)
        }

        assertEquals(previous, ranking)
        assertFalse(rankingApplied)
        assertEquals(CoverPreloadStatus.FAILED, progress.status)
        assertFalse(progress.popularityUpdated)
        assertEquals(0, progress.processed)
    }

    private fun rank(id: String, value: Int) = LocalPopularityRank(id, value)
    private fun incoming(id: String, value: Int) = IncomingPopularityRank(id, value)
}
