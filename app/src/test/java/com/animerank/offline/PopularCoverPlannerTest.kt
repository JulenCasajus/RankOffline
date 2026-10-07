package com.animerank.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopularCoverPlannerTest {
    @Test
    fun selectionHonorsTopNAndPopularityOrder() {
        val entries = (1L..5L).map(::entry)
        val local = (1L..5L).map(::anime)

        val plan = PopularCoverPlanner.plan(entries, local, 3)

        assertEquals(3, plan.target)
        assertEquals(listOf(1L, 2L, 3L), plan.pending.map { it.anime.anilistId })
    }

    @Test
    fun existingCoversAreNotScheduledAgain() {
        val entries = (1L..3L).map(::entry)
        val local = listOf(
            anime(1, "/private/cover-1.jpg"),
            anime(2),
            anime(3, "/private/cover-3.jpg")
        )

        val plan = PopularCoverPlanner.plan(entries, local, 3)

        assertEquals(2, plan.existing)
        assertEquals(listOf(2L), plan.pending.map { it.anime.anilistId })
    }

    @Test
    fun expandingTop100ToTop500OnlySchedulesNewCovers() {
        val entries = (1L..500L).map(::entry)
        val local = (1L..500L).map { id ->
            anime(id, if (id <= 100) "/private/$id.jpg" else null)
        }

        val plan = PopularCoverPlanner.plan(entries, local, 500)

        assertEquals(100, plan.existing)
        assertEquals(400, plan.pending.size)
        assertTrue(plan.pending.all { requireNotNull(it.anime.anilistId) > 100 })
    }

    @Test
    fun uniqueMalIdSafelyMatchesWhenLocalAniListIdIsMissing() {
        val entry = PopularAnimeEntry(999, 42, 1000, "https://example.com/cover.jpg")
        val local = listOf(Anime(id = "mal:42", title = "Different title", malId = 42))

        val plan = PopularCoverPlanner.plan(listOf(entry), local, 1)

        assertEquals(listOf("mal:42"), plan.pending.map { it.anime.id })
        assertEquals(0, plan.unmatched)
    }

    @Test
    fun conflictingAniListIdPreventsMalFallback() {
        val entry = PopularAnimeEntry(999, 42, 1000, "https://example.com/cover.jpg")
        val local = listOf(Anime(id = "mal:42", title = "Different title", malId = 42, anilistId = 1000))

        val plan = PopularCoverPlanner.plan(listOf(entry), local, 1)

        assertTrue(plan.pending.isEmpty())
        assertEquals(1, plan.unmatched)
    }

    private fun entry(id: Long) = PopularAnimeEntry(
        anilistId = id,
        malId = id + 10_000,
        popularity = (100_000L - id).toInt(),
        coverUrl = "https://example.com/$id.jpg"
    )

    private fun anime(id: Long, localPath: String? = null) = Anime(
        id = "anilist:$id",
        title = "Anime $id",
        anilistId = id,
        localCoverPath = localPath
    )
}
