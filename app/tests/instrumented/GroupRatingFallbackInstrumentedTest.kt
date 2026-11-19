package org.rankoffline.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupRatingFallbackInstrumentedTest {
    private lateinit var database: RankOfflineDatabase

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), RankOfflineDatabase::class.java
        ).build()
    }

    @After fun close() = database.close()

    @Test fun deletingExplicitGroupRatingRestoresMemberAverageInSearchRankingAndDetail() = runBlocking {
        val manifest = TestFixtures.manifest(11, merge = true)
        database.mediaCatalogDao().insertNewItems(listOf(
            MediaItemEntity(11, "a", "First"), MediaItemEntity(11, "b", "Second")
        ))
        database.mediaGroupDao().insertNewGroups(listOf(
            MediaGroupEntity(11, "group", "relations", "Group")
        ))
        database.mediaGroupDao().insertMemberships(listOf(
            MediaGroupMemberEntity(11, "group", "a", 0),
            MediaGroupMemberEntity(11, "group", "b", 1)
        ))
        val personal = database.personalDataDao()
        personal.upsertRatings(listOf(
            MediaRatingEntity(11, "a", 0.1, 7.0, 7.0, "", "", 1),
            MediaRatingEntity(11, "b", 0.3, 9.0, 9.0, "", "", 1)
        ))
        personal.upsertRatingScores(listOf(
            MediaRatingScoreEntity(11, "a", "writing", 7.0, true),
            MediaRatingScoreEntity(11, "b", "writing", 9.0, true)
        ))
        personal.upsertGroupRatings(listOf(
            MediaGroupRatingEntity(11, "group", 0.0, 10.0, 10.0, "", "", 1)
        ))
        personal.upsertGroupRatingScores(listOf(
            MediaGroupRatingScoreEntity(11, "group", "writing", 10.0, true)
        ))
        val ratings = RatingRepository(database)
        val repository = MediaRepository(database, ratings)
        val key = CatalogEntryKey(PackId(11), "group", CatalogEntryKind.GROUP)
        assertEquals(10.0, repository.search(manifest, SearchUiState(), true).single().finalScore!!, 0.0)

        ratings.deleteRating(key)

        val search = repository.search(manifest, SearchUiState(), true).single() as MediaListEntry.Group
        val ranking = repository.ranking(manifest, RankingUiState(), true).single() as MediaListEntry.Group
        val categoryRanking = repository.ranking(manifest, RankingUiState(
            sortOrder = RankingSortOrder.CATEGORY_SCORE, ratingCategoryId = "writing"), true)
            .single() as MediaListEntry.Group
        val allSearch = repository.searchAllPage(listOf(manifest),
            mapOf(manifest.packId to SearchUiState()), mapOf(manifest.packId to true), 200)
            .entries.single()
        val allRanking = repository.rankingAllPage(listOf(manifest), RankingUiState(),
            mapOf(manifest.packId to true), 200).entries.single()
        val detail = GroupRepository(database, ratings).detail(key)!!
        assertEquals(8.0, search.finalScore!!, 0.0)
        assertEquals(8.0, ranking.finalScore!!, 0.0)
        assertEquals(8.0, search.qualityScore!!, 0.0)
        assertEquals(0.2, search.additiveScore!!, 0.000001)
        assertEquals(8.0, categoryRanking.categoryScore!!, 0.0)
        assertEquals(8.0, allSearch.finalScore!!, 0.0)
        assertEquals(8.0, allRanking.finalScore!!, 0.0)
        assertEquals(2, search.ratedCount)
        assertNull(detail.explicitRating)
        assertEquals(8.0, detail.derivedRating.finalScore!!, 0.0)
        assertEquals(7.0, database.mediaDao().rating(11, "a")?.finalScore!!, 0.0)
        assertEquals(9.0, database.mediaDao().rating(11, "b")?.finalScore!!, 0.0)

        ratings.deleteRating(CatalogEntryKey(PackId(11), "a", CatalogEntryKind.ITEM))
        ratings.deleteRating(CatalogEntryKey(PackId(11), "b", CatalogEntryKind.ITEM))
        assertNull(repository.search(manifest, SearchUiState(), true).single().finalScore)
        assertEquals(emptyList<MediaListEntry>(), repository.ranking(manifest, RankingUiState(), true))
    }

    @Test fun itemAliasDoesNotMakeGroupMatchWithoutItsOwnTitle() = runBlocking {
        val manifest = TestFixtures.manifest(11, merge = true)
        database.mediaCatalogDao().insertNewItems(listOf(MediaItemEntity(11, "a", "First")))
        database.mediaCatalogDao().insertAliases(listOf(MediaAliasEntity(11, "a", "Alternative")))
        database.mediaGroupDao().insertNewGroups(listOf(
            MediaGroupEntity(11, "group", "relations", "Group")
        ))
        database.mediaGroupDao().insertMemberships(listOf(MediaGroupMemberEntity(11, "group", "a", 0)))
        val repository = MediaRepository(database, RatingRepository(database))

        assertEquals("Alternative", repository.search(manifest,
            SearchUiState(query = "Alternat"), false).single().title)
        assertEquals(emptyList<MediaListEntry>(), repository.search(manifest,
            SearchUiState(query = "Alternat"), true))
        assertEquals("Group", repository.search(manifest,
            SearchUiState(query = "Group"), true).single().title)
        assertEquals(emptyList<MediaListEntry>(), repository.searchAllPage(listOf(manifest),
            mapOf(manifest.packId to SearchUiState(query = "Alternat")),
            mapOf(manifest.packId to true), 200).entries)
        assertEquals("Alternative", repository.searchAllPage(listOf(manifest),
            mapOf(manifest.packId to SearchUiState(query = "Alternat")),
            mapOf(manifest.packId to false), 200).entries.single().title)
    }

    @Test fun derivedDraftFallsBackFromRemovedProfileWithoutChangingMemberRating() = runBlocking {
        database.mediaCatalogDao().insertNewItems(listOf(MediaItemEntity(11, "a", "First")))
        database.mediaGroupDao().insertNewGroups(listOf(MediaGroupEntity(11, "group", "relations", "Group")))
        database.mediaGroupDao().insertMemberships(listOf(MediaGroupMemberEntity(11, "group", "a", 0)))
        val member = MediaRatingEntity(11, "a", 0.0, 8.0, 8.0, "", "", 1, "removed")
        database.personalDataDao().upsertRatings(listOf(member))
        val ratings = RatingRepository(database)
        val key = CatalogEntryKey(PackId(11), "group", CatalogEntryKind.GROUP)
        val profiles = TestFixtures.ratingDefinition().profiles

        assertEquals("general", ratings.loadRating(key, fromMembers = true,
            availableProfiles = profiles).profileId)
        assertEquals(member, database.mediaDao().rating(11, "a"))
        database.personalDataDao().upsertGroupRatings(listOf(
            MediaGroupRatingEntity(11, "group", 0.0, 9.0, 9.0, "", "", 2, "removed")
        ))
        assertEquals("removed", ratings.loadRating(key, fromMembers = true,
            availableProfiles = profiles).profileId)
    }

    @Test fun notesOnlyGroupKeepsMetadataWhileMemberDraftRemainsAvailable() = runBlocking {
        database.mediaCatalogDao().insertNewItems(listOf(MediaItemEntity(11, "a", "First")))
        database.mediaGroupDao().insertNewGroups(listOf(MediaGroupEntity(11, "group", "relations", "Group")))
        database.mediaGroupDao().insertMemberships(listOf(MediaGroupMemberEntity(11, "group", "a", 0)))
        database.personalDataDao().upsertRatings(listOf(
            MediaRatingEntity(11, "a", 0.0, 8.0, 8.0, "", "", 1)
        ))
        val ratings = RatingRepository(database)
        val key = CatalogEntryKey(PackId(11), "group", CatalogEntryKind.GROUP)
        ratings.saveMetadata(key, "Keep this note", "watching")

        val fresh = ratings.loadRating(key, fromMembers = false)
        val derived = ratings.loadRating(key, fromMembers = true)
        assertNull(fresh.finalScore)
        assertEquals("Keep this note", fresh.notes)
        assertEquals("watching", fresh.status)
        assertEquals(8.0, derived.qualityScore!!, 0.0)
        assertEquals("Keep this note", derived.notes)
        assertEquals("watching", derived.status)
        assertEquals(8.0, database.mediaDao().rating(11, "a")?.finalScore!!, 0.0)
        assertNull(database.mediaGroupDao().rating(11, "group")?.finalScore)
    }
}
