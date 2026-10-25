package org.rankoffline.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenericMediaGroupRepositoryTest {
    private lateinit var database: AnimeDatabase
    private lateinit var mediaRepository: GenericMediaRepository
    private lateinit var groupRepository: GenericMediaGroupRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AnimeDatabase::class.java
        ).build()
        mediaRepository = GenericMediaRepository(database, now = { 100L })
        groupRepository = GenericMediaGroupRepository(database) { 200L }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun explicitGroupRatingIsIndependentFromMembers() = runBlocking {
        val fixture = createGroup(MediaType.SERIES, "series:tmdb:10", 2)
        saveMember(fixture.members[0].mediaId, 8.0)
        saveMember(fixture.members[1].mediaId, 6.0)
        assertEquals(7.0, groupRepository.groupDetail(fixture.group.id)!!.derivedRating.finalScore!!, 0.0)

        groupRepository.saveSimpleGroupRating(
            fixture.group.id,
            RatingDraft(notes = "explicit"),
            qualityScore = 9.0,
            finalScore = 9.0
        )
        saveMember(fixture.members[0].mediaId, 10.0)

        val detail = groupRepository.groupDetail(fixture.group.id)!!
        assertEquals(9.0, detail.explicitRating!!.finalScore!!, 0.0)
        assertEquals(8.0, detail.derivedRating.finalScore!!, 0.0)
        assertEquals(6.0, mediaRepository.rating(fixture.members[1].mediaId)!!.finalScore!!, 0.0)
    }

    @Test
    fun simpleGroupSavePreservesElaborateDetails() = runBlocking {
        val fixture = createGroup(MediaType.MANGA, "manga:mangadex:variants", 2)
        groupRepository.saveElaborateGroupRating(
            fixture.group.id,
            RatingDraft(
                categoryScores = mapOf("writing" to 8.0, "characters" to null),
                additiveScore = 0.4
            ),
            twoCategoryConfig(),
            profileId = "narrative"
        )
        val loaded = groupRepository.loadGroupRating(fixture.group.id, fromMembers = false)

        groupRepository.saveSimpleGroupRating(fixture.group.id, loaded, 7.0, 7.4)

        val detail = groupRepository.groupDetail(fixture.group.id)!!
        val scores = groupRepository.groupRatingScores(fixture.group.id).associateBy { it.categoryId }
        assertEquals(7.4, detail.explicitRating!!.finalScore!!, 0.0)
        assertEquals("narrative", detail.explicitRating.profileId)
        assertEquals(8.0, scores.getValue("writing").score!!, 0.0)
        assertEquals(false, scores.getValue("characters").applicable)
    }

    @Test
    fun deleteExplicitGroupRatingFallsBackToDerived() = runBlocking {
        val fixture = createGroup(MediaType.MOVIE, "movie:tmdb:collection:7", 2)
        saveMember(fixture.members[0].mediaId, 8.0)
        saveMember(fixture.members[1].mediaId, 6.0)

        val derivedOnly = groupRepository.groupDetail(fixture.group.id)!!
        assertNull(derivedOnly.explicitRating)
        assertEquals(7.0, derivedOnly.derivedRating.finalScore!!, 0.0)

        groupRepository.saveSimpleGroupRating(fixture.group.id, RatingDraft(), 9.0, 9.0)
        groupRepository.deleteGroupRating(fixture.group.id)

        val fallback = groupRepository.groupDetail(fixture.group.id)!!
        assertNull(fallback.explicitRating)
        assertEquals(7.0, fallback.derivedRating.finalScore!!, 0.0)
        assertEquals(2, fallback.members.size)
        assertNotNull(mediaRepository.rating(fixture.members[0].mediaId))
        assertNotNull(mediaRepository.rating(fixture.members[1].mediaId))
    }

    @Test
    fun groupCategoryCleanupIsMediaScoped() = runBlocking {
        val manga = createGroup(MediaType.MANGA, "manga:mangadex:group", 1)
        val book = createGroup(MediaType.BOOK, "book:openlibrary:work", 1)
        listOf(manga, book).forEach { fixture ->
            groupRepository.saveElaborateGroupRating(
                fixture.group.id,
                RatingDraft(categoryScores = mapOf("writing" to 8.0)),
                singleCategoryConfig(),
                profileId = "general"
            )
        }

        groupRepository.deleteGroupRatingCategory(MediaType.MANGA, "writing")

        assertEquals(0, groupRepository.groupRatingScores(manga.group.id).size)
        assertEquals(1, groupRepository.groupRatingScores(book.group.id).size)
        assertEquals(8.0, groupRepository.groupRatingScores(book.group.id).single().score!!, 0.0)
    }

    private suspend fun createGroup(
        type: MediaType,
        groupId: String,
        memberCount: Int
    ): GroupFixture {
        val items = (1..memberCount).map { index ->
            MediaItemEntity(
                id = "$groupId:member:$index",
                mediaType = type.persistedValue,
                provider = provider(type),
                providerId = "$groupId-$index",
                title = "Member $index",
                partNumber = index
            )
        }
        mediaRepository.upsertItems(items)
        val group = MediaGroupEntity(
            id = groupId,
            mediaType = type.persistedValue,
            groupType = "TEST",
            displayTitle = "Test Group",
            representativeMediaId = items.first().id
        )
        val members = items.mapIndexed { index, item -> MediaGroupMemberEntity(groupId, item.id, index) }
        groupRepository.upsertGroup(group, members)
        return GroupFixture(group, members)
    }

    private suspend fun saveMember(mediaId: String, score: Double) {
        mediaRepository.saveSimpleRating(mediaId, RatingDraft(), score, score)
    }

    private fun provider(type: MediaType) = when (type) {
        MediaType.MANGA -> "mangadex"
        MediaType.BOOK -> "openlibrary"
        else -> "tmdb"
    }

    private fun singleCategoryConfig() = RatingSystemConfig(
        listOf(RatingCategory("writing", "Writing", "", 100.0, "#000000", 0)),
        AdditiveBonus("Taste", "", 1.0, "#000000")
    )

    private fun twoCategoryConfig() = RatingSystemConfig(
        listOf(
            RatingCategory("writing", "Writing", "", 50.0, "#000000", 0),
            RatingCategory("characters", "Characters", "", 50.0, "#000000", 1)
        ),
        AdditiveBonus("Taste", "", 1.0, "#000000")
    )

    private data class GroupFixture(
        val group: MediaGroupEntity,
        val members: List<MediaGroupMemberEntity>
    )
}
