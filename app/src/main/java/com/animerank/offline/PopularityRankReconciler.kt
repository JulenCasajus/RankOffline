package com.animerank.offline

data class LocalPopularityRank(val animeId: String, val rank: Int)

data class IncomingPopularityRank(val animeId: String, val rank: Int)

data class PopularityRankPlan(
    val finalRanks: Map<String, Int>,
    val changedRanks: Map<String, Int>
)

object PopularityRankReconciler {
    const val FULL_RANKING_LIMIT = 5_000

    fun reconcile(
        previous: List<LocalPopularityRank>,
        incoming: List<IncomingPopularityRank>,
        limit: Int
    ): PopularityRankPlan {
        require(limit in 1..FULL_RANKING_LIMIT)
        require(previous.all { it.rank > 0 })
        require(previous.map { it.animeId }.distinct().size == previous.size)
        require(previous.map { it.rank }.distinct().size == previous.size)
        require(incoming.all { it.rank in 1..limit })
        require(incoming.map { it.animeId }.distinct().size == incoming.size)
        require(incoming.map { it.rank }.distinct().size == incoming.size)

        val previousRanks = previous.associate { it.animeId to it.rank }
        val incomingRanks = incoming.associate { it.animeId to it.rank }
        val finalRanks = if (limit == FULL_RANKING_LIMIT) {
            reconcileFull(previous, incomingRanks)
        } else {
            reconcilePartial(previous, previousRanks, incomingRanks, limit)
        }
        check(finalRanks.values.all { it > 0 })
        check(finalRanks.values.distinct().size == finalRanks.size)
        val changed = finalRanks.filter { (animeId, rank) -> previousRanks[animeId] != rank }
        return PopularityRankPlan(finalRanks, changed)
    }

    private fun reconcilePartial(
        previous: List<LocalPopularityRank>,
        previousRanks: Map<String, Int>,
        incomingRanks: Map<String, Int>,
        limit: Int
    ): Map<String, Int> {
        val incomingIds = incomingRanks.keys
        val displaced = previous
            .filter { it.rank <= limit && it.animeId !in incomingIds }
            .sortedBy { it.rank }
        val freedRanks = incomingIds.mapNotNull { animeId ->
            previousRanks[animeId]?.takeIf { it > limit }
        }.sorted()

        val result = previousRanks.toMutableMap()
        incomingRanks.forEach { (animeId, rank) -> result[animeId] = rank }
        var nextTailRank = maxOf(
            previous.maxOfOrNull { it.rank } ?: limit,
            incomingRanks.values.maxOrNull() ?: limit
        ) + 1
        displaced.forEachIndexed { index, anime ->
            result[anime.animeId] = freedRanks.getOrNull(index) ?: nextTailRank++
        }
        return result
    }

    private fun reconcileFull(
        previous: List<LocalPopularityRank>,
        incomingRanks: Map<String, Int>
    ): Map<String, Int> {
        val incomingIds = incomingRanks.keys
        val expelled = previous
            .filter { it.rank <= FULL_RANKING_LIMIT && it.animeId !in incomingIds }
            .sortedBy { it.rank }
        val existingTail = previous
            .filter { it.rank > FULL_RANKING_LIMIT && it.animeId !in incomingIds }
            .sortedBy { it.rank }
        val result = incomingRanks.toMutableMap()
        (expelled + existingTail).forEachIndexed { index, anime ->
            result[anime.animeId] = FULL_RANKING_LIMIT + index + 1
        }
        return result
    }
}

fun validatePopularAnimeResponse(entries: List<PopularAnimeEntry>, expectedCount: Int) {
    val validIds = entries.count { it.anilistId > 0 }
    val duplicateIds = entries.size - entries.map { it.anilistId }.distinct().size
    require(entries.size == expectedCount) {
        "AniList returned ${entries.size} of $expectedCount requested anime " +
            "(validIds=$validIds, duplicateIds=$duplicateIds)"
    }
    require(validIds == entries.size && duplicateIds == 0) {
        "AniList Top $expectedCount is invalid (validIds=$validIds, duplicateIds=$duplicateIds)"
    }
}
