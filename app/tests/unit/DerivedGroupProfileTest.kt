package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DerivedGroupProfileTest {
    private fun rating(id: String, profile: String?, score: Double?) =
        MediaRatingEntity(11, id, 0.0, score, score, "", "", 1, profile)

    @Test fun majorityIgnoresUnratedAndNullProfiles() {
        val derived = deriveMediaGroupRating(listOf(
            rating("a", "profile_a", 8.0), rating("b", "profile_a", 7.0),
            rating("c", "general", 9.0), rating("d", null, 6.0),
            rating("ignored", "profile_b", null)
        ), emptyList())
        assertEquals("profile_a", derived.predominantProfileId)
        assertEquals("profile_a", derived.asDraft().profileId)
    }

    @Test fun tiesPreferGeneralThenStableId() {
        val tie = deriveMediaGroupRating(listOf(
            rating("a", "profile_b", 8.0), rating("b", "general", 7.0)
        ), emptyList())
        assertEquals("general", tie.predominantProfileId)
        val stable = deriveMediaGroupRating(listOf(
            rating("a", "z", 8.0), rating("b", "a", 7.0)
        ), emptyList())
        assertEquals("a", stable.predominantProfileId)
        assertNull(deriveMediaGroupRating(listOf(rating("a", null, 8.0)), emptyList())
            .predominantProfileId)
    }
}
