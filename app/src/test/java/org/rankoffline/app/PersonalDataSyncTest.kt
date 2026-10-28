package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalDataSyncTest {
    private val identity = OwnedMediaIdentity(OfficialPackIds.Anime, "mal:1")
    private val external = ExternalWorkId("myanimelist", "1")

    @Test
    fun conversionPreservesOriginalAndDryRunIsIdempotent() {
        val record = ExternalRatingRecord(external, 80.0, RatingScale(0.0, 100.0), 20L)
        val preview = previewExternalRatings(
            listOf(record),
            mapOf(external to setOf(identity)),
            emptyMap()
        )
        assertEquals(8.0, preview.applicable.single().convertedScore, 0.0)
        assertEquals(80.0, preview.applicable.single().originalScore, 0.0)

        val repeated = previewExternalRatings(
            listOf(record),
            mapOf(external to setOf(identity)),
            mapOf(identity to 20L)
        )
        assertTrue(repeated.applicable.isEmpty())
        assertEquals(1, repeated.unchanged)
    }

    @Test
    fun missingAmbiguousAndInvalidMappingsAreReportedWithoutGuessing() {
        val missing = ExternalRatingRecord(
            ExternalWorkId("anilist", "9"), 5.0, RatingScale(0.0, 10.0), 1L
        )
        val ambiguous = ExternalRatingRecord(external, 5.0, RatingScale(0.0, 10.0), 1L)
        val invalid = ExternalRatingRecord(
            ExternalWorkId("myanimelist", "2"), 11.0, RatingScale(0.0, 10.0), 1L
        )
        val result = previewExternalRatings(
            listOf(missing, ambiguous, invalid),
            mapOf(
                external to setOf(identity, OwnedMediaIdentity(OfficialPackIds.Manga, "mal:1")),
                invalid.externalId to setOf(identity)
            ),
            emptyMap()
        )
        assertEquals(1, result.missing)
        assertEquals(1, result.ambiguous)
        assertEquals(1, result.invalid)
        assertTrue(result.applicable.isEmpty())
    }
}
