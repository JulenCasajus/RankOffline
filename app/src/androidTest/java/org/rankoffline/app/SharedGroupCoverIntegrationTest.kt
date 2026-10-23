package org.rankoffline.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedGroupCoverIntegrationTest {
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
    fun membersAndGroupUseFirstOrderedMemberWithoutChangingOriginalIdentity() = runBlocking {
        val repository = GenericMediaRepository(database)
        val groupRepository = GenericMediaGroupRepository(database)
        val first = movie("first", popularityRank = 2)
        val second = movie("second", popularityRank = 1)
        val ungrouped = movie("ungrouped", popularityRank = 3)
        repository.upsertItems(listOf(first, second, ungrouped))
        groupRepository.upsertGroup(
            group("collection", first.id, memberCount = 2, popularityRank = 1),
            listOf(
                MediaGroupMemberEntity("movie:group:collection", second.id, 4),
                MediaGroupMemberEntity("movie:group:collection", first.id, 1)
            )
        )

        val individuals = repository.search(
            MediaType.MOVIE, "", CatalogSortOrder.POPULARITY,
            GenericMediaFilters(), mergeEnabled = false
        )
        val firstRow = individuals.filterIsInstance<MediaListEntry.Item>()
            .first { it.id == first.id }
        val secondRow = individuals.filterIsInstance<MediaListEntry.Item>()
            .first { it.id == second.id }
        val ungroupedRow = individuals.filterIsInstance<MediaListEntry.Item>()
            .first { it.id == ungrouped.id }
        assertEquals(first.id, firstRow.value.sharedCoverMediaId)
        assertEquals(first.id, secondRow.value.sharedCoverMediaId)
        assertNull(ungroupedRow.value.sharedCoverMediaId)

        val merged = repository.search(
            MediaType.MOVIE, "", CatalogSortOrder.POPULARITY,
            GenericMediaFilters(), mergeEnabled = true
        )
        val groupRow = merged.filterIsInstance<MediaListEntry.Group>().single()
        assertEquals(first.id, groupRow.sharedCoverMediaId)

        val detail = requireNotNull(groupRepository.groupDetail(groupRow.id))
        assertEquals(listOf(first.id, first.id), detail.members.map { it.sharedCoverMediaId })
    }

    @Test
    fun top250IsAppliedAfterLogicalGroupingAndNoGroupPackStaysIndividual() = runBlocking {
        val movieItems = ArrayList<MediaItemEntity>(520)
        val groups = ArrayList<MediaGroupEntity>(260)
        val memberships = ArrayList<MediaGroupMemberEntity>(520)
        repeat(260) { index ->
            val first = movie("g${index}-first", popularityRank = index * 2 + 1)
            val second = movie("g${index}-second", popularityRank = index * 2 + 2)
            val groupId = "movie:group:$index"
            movieItems += first
            movieItems += second
            groups += group(groupId.removePrefix("movie:group:"), first.id, 2, index + 1)
            memberships += MediaGroupMemberEntity(groupId, second.id, 2)
            memberships += MediaGroupMemberEntity(groupId, first.id, 0)
        }
        database.mediaDao().upsertItems(movieItems)
        database.mediaGroupDao().insertNewGroups(groups)
        database.mediaGroupDao().insertMembers(memberships)

        val covers = GenericMediaCoverRepository(
            InstrumentationRegistry.getInstrumentation().targetContext,
            database
        )
        val individualTop = covers.topPopularCoverItems(MediaType.MOVIE, 250, false)
        val logicalTop = covers.topPopularCoverItems(MediaType.MOVIE, 250, true)

        assertEquals(250, individualTop.size)
        assertEquals("movie:tmdb:g0-first", individualTop[0].id)
        assertEquals("movie:tmdb:g0-second", individualTop[1].id)
        assertEquals(250, logicalTop.size)
        assertEquals(250, logicalTop.map { it.id }.toSet().size)
        assertEquals((0 until 250).map { "movie:tmdb:g$it-first" }, logicalTop.map { it.id })

        val books = (1..260).map { index ->
            MediaItemEntity(
                id = "book:openlibrary:$index",
                mediaType = MediaType.BOOK.persistedValue,
                provider = "openlibrary",
                providerId = index.toString(),
                title = "Book $index",
                popularityRank = index
            )
        }
        database.mediaDao().upsertItems(books)
        assertEquals(
            (1..250).map { "book:openlibrary:$it" },
            covers.topPopularCoverItems(MediaType.BOOK, 250, true).map { it.id }
        )
    }

    @Test
    fun oneCoverPerGroupSettingIsPackScopedAndSurvivesRepositoryRecreation() = runBlocking {
        val context = IsolatedTestContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "shared-group-cover-settings"
        )
        val icons = object : AppIconAliasController {
            override fun activate(icon: AppIcon, previousIcon: AppIcon) = Unit
            override fun activeIconOrNull(): AppIcon = AppIcon.DARK
        }
        val locale = AppLocaleController { }
        try {
            val settings = SettingsRepository(context, icons, locale)
            settings.loadInitialState()
            settings.updateActiveMediaType(MediaType.MOVIE)
            settings.updateOneCoverPerGroup(true)

            val recreated = SettingsRepository(context, icons, locale)
            recreated.loadInitialState()
            assertTrue(recreated.state.value.mediaSettings.getValue(MediaType.MOVIE).oneCoverPerGroup)
            assertFalse(recreated.state.value.mediaSettings.getValue(MediaType.ANIME).oneCoverPerGroup)
        } finally {
            context.delete()
        }
    }

    private fun movie(providerId: String, popularityRank: Int) = MediaItemEntity(
        id = "movie:tmdb:$providerId",
        mediaType = MediaType.MOVIE.persistedValue,
        provider = "tmdb",
        providerId = providerId,
        title = providerId,
        popularityRank = popularityRank,
        remoteCoverRef = "/$providerId.jpg"
    )

    private fun group(
        providerId: String,
        representativeId: String,
        memberCount: Int,
        popularityRank: Int
    ) = MediaGroupEntity(
        id = "movie:group:$providerId",
        mediaType = MediaType.MOVIE.persistedValue,
        groupType = "COLLECTION",
        provider = "tmdb",
        providerGroupId = providerId,
        displayTitle = providerId,
        representativeMediaId = representativeId,
        memberCount = memberCount,
        popularityRank = popularityRank
    )
}
