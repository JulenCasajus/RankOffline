package org.rankoffline.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A small isolated Room fixture: no downloaded packs, production catalog, or network calls. */
@RunWith(AndroidJUnit4::class)
class TopLogicalCoverPopularityOrderingTest {
    private lateinit var database: AnimeDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AnimeDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun rankedGroupsComeBeforeUnrankedItems_evenWhenUnrankedItemsHavePopularity() = runBlocking {
        val mediaType = MediaType.MOVIE.persistedValue
        val groupType = requireNotNull(MediaType.MOVIE.genericGroupType())
        fun item(id: String, rank: Int?, popularity: Double?) = MediaItemEntity(
            id = id,
            mediaType = mediaType,
            provider = "fixture",
            providerId = id,
            title = id,
            popularityRank = rank,
            popularity = popularity
        )

        database.mediaCatalogDao().insertNewItems(
            listOf(
                item("ranked-group-first", 1, 100.0),
                item("ranked-group-second", 2, 95.0),
                item("ranked-ungrouped", 2, 20.0),
                item("unranked-high", null, 500.0),
                item("unranked-low", null, 1.0),
                item("unknown-popularity", null, null)
            )
        )
        val groupId = "fixture-group"
        database.mediaGroupDao().upsertGroupWithMembers(
            MediaGroupEntity(
                id = groupId,
                mediaType = mediaType,
                groupType = groupType,
                displayTitle = "Ranked group",
                // Intentionally distinct: representative is NOT the ordered first member.
                representativeMediaId = "ranked-group-second",
                memberCount = 2,
                popularityRank = 1,
                popularity = 100.0
            ),
            listOf(
                MediaGroupMemberEntity(groupId, "ranked-group-second", 1),
                MediaGroupMemberEntity(groupId, "ranked-group-first", 0)
            )
        )

        val ids = database.mediaGroupDao().topLogicalCoverItems(mediaType, groupType, 100)
            .map(MediaItemEntity::id)
        assertEquals(
            listOf(
                "ranked-group-first", "ranked-ungrouped", "unranked-high",
                "unranked-low", "unknown-popularity"
            ),
            ids
        )
        // The SQL LIMIT applies to logical group/ungrouped candidates, not to group members.
        assertEquals(
            listOf("ranked-group-first", "ranked-ungrouped"),
            database.mediaGroupDao().topLogicalCoverItems(mediaType, groupType, 2)
                .map(MediaItemEntity::id)
        )
    }
}
