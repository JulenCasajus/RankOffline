package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GroupRatingTest {
    private fun rating(id: String, final: Double?, quality: Double? = final, bonus: Double = 0.0) =
        RatingEntity(id, bonus, quality, final, "note $id", "Completed", 1)

    @Test fun derivedAveragesOnlyRatedAndApplicableMembers() {
        val ratings = listOf(rating("a", 9.0, 8.0, 0.5), rating("b", 7.0, null, 0.1), rating("c", null))
        val scores = listOf(
            RatingScoreEntity("a", "visuals", 10.0, true),
            RatingScoreEntity("b", "visuals", null, false),
            RatingScoreEntity("c", "visuals", 1.0, true)
        )
        val derived = deriveGroupRating(ratings, scores)
        assertEquals(2, derived.ratedCount)
        assertEquals(8.0, derived.finalScore!!, 0.0)
        assertEquals(8.0, derived.qualityScore!!, 0.0)
        assertEquals(0.3, derived.additiveScore!!, 0.000001)
        assertEquals(10.0, derived.categoryScores.getValue("visuals"), 0.0)
        assertFalse(derived.categoryScores.containsKey("missing"))
    }

    @Test fun emptyGroupIsUnratedAndDerivedDraftDoesNotPersist() {
        val derived = deriveGroupRating(emptyList(), emptyList())
        assertNull(derived.finalScore)
        assertEquals(0, derived.ratedCount)
        assertNull(derived.asDraft().finalScore)
    }

    @Test fun independentExplicitGroupValueIsNotCalculatedFromMembers() {
        val explicit = GroupRatingEntity("group:mal:1:SERIES", 0.4, 9.0, 9.4, "global", "Completed", 1)
        val before = deriveGroupRating(listOf(rating("a", 9.0), rating("b", 8.5)), emptyList())
        val after = deriveGroupRating(listOf(rating("a", 9.0), rating("b", 9.0)), emptyList())
        assertEquals(9.4, explicit.finalScore!!, 0.0)
        assertFalse(before.finalScore == after.finalScore)
    }

    @Test fun mergeSeasonsDefaultsOffAndNormalizedFormatsAreStable() {
        assertFalse(resolveMergeSeasonsEnabled(null))
        assertEquals("SERIES", normalizedAnimeFormat("TV"))
        assertEquals("SERIES", normalizedAnimeFormat("TV_SHORT"))
        assertEquals("SERIES", normalizedAnimeFormat("ONA"))
        assertEquals("SERIES", normalizedAnimeFormat("SERIES"))
        assertEquals("MOVIES", normalizedAnimeFormat("movie"))
        assertEquals("MOVIES", normalizedAnimeFormat("MOVIES"))
        assertEquals("SPECIAL", normalizedAnimeFormat("OVA"))
        assertEquals("SPECIAL", normalizedAnimeFormat("SPECIAL"))
        assertEquals("MUSIC", normalizedAnimeFormat("MUSIC"))
    }

    @Test fun formatFilterExposesOnlyAvailableNormalizedFormatsInCanonicalOrder() {
        assertEquals(
            listOf("SERIES", "MOVIES", "SPECIAL", "MUSIC"),
            normalizedFormatOptions(listOf("OVA", "TV", "MOVIE", "ONA", "MUSIC", "TV_SHORT", "SPECIAL"))
        )
        assertEquals(listOf("SERIES", "SPECIAL"), normalizedFormatOptions(listOf("TV", "ONA", "OVA")))
    }

    @Test fun metadataSubtitleOmitsMissingSegmentsWithoutEmptySeparators() {
        assertEquals(listOf("Series", "2023", "Madhouse"), metadataSubtitleSegments("Series", 2023, "Madhouse"))
        assertEquals("Series · Madhouse", joinMetadataSubtitle(metadataSubtitleSegments("Series", null, "Madhouse")))
        assertEquals("2023 · Madhouse", joinMetadataSubtitle(metadataSubtitleSegments(null, 2023, "Madhouse")))
        assertEquals("Series", joinMetadataSubtitle(metadataSubtitleSegments("Series", null, null)))
        assertEquals("2023", joinMetadataSubtitle(metadataSubtitleSegments(null, 2023, null)))
        assertEquals("", joinMetadataSubtitle(metadataSubtitleSegments(null, null, null)))
    }

    @Test fun metadataSubtitleMatchesFilterOrderForEveryMediaType() {
        assertEquals(
            listOf("Action", "Series", "2023", "Madhouse"),
            filterMetadataSubtitleSegments(MediaType.ANIME, "Action", "Series", 2023, "Madhouse")
        )
        for (mediaType in listOf(MediaType.MANGA, MediaType.SERIES, MediaType.MOVIE, MediaType.BOOK)) {
            assertEquals(
                listOf("Drama", "2024", "Credit"),
                filterMetadataSubtitleSegments(mediaType, "Drama", "ignored", 2024, "Credit")
            )
        }
    }

    @Test fun groupScoreLabelRequiresAnActualMultiMemberGroup() {
        assertEquals(true, usesGroupScoreLabel(isGroup = true, memberCount = 2))
        assertEquals(false, usesGroupScoreLabel(isGroup = true, memberCount = 1))
        assertEquals(false, usesGroupScoreLabel(isGroup = false, memberCount = 2))
    }

    @Test fun coverViewerUsesEntryLinkOnlyForActualMultiMemberGroups() {
        val group = Anime(id = "group:1", title = "Group", groupId = "group:1", memberCount = 2)

        assertEquals(true, group.usesGroupEntriesCoverSubtitle())
        assertEquals(false, group.copy(memberCount = 1).usesGroupEntriesCoverSubtitle())
        assertEquals(false, group.copy(groupId = null).usesGroupEntriesCoverSubtitle())
    }

    @Test fun groupRowRoutesMainAreaToRatingAndMemberCountToDetails() {
        val group = Anime(id = "group:1:SERIES", title = "Example", groupId = "group:1:SERIES", memberCount = 2)
        val individual = Anime(id = "1", title = "Example")
        assertEquals(AnimeListRowAction.RATE, animeListRowAction(group, memberCountClicked = false))
        assertEquals(AnimeListRowAction.GROUP_DETAILS, animeListRowAction(group, memberCountClicked = true))
        assertEquals(AnimeListRowAction.RATE, animeListRowAction(individual, memberCountClicked = true))
    }
}
