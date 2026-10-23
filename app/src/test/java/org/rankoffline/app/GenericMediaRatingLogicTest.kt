package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericMediaRatingLogicTest {
    @Test
    fun genericEntityMappingIncludesRatingAndSafelyParsesCoverState() {
        val entity = MediaItemEntity(
            id = "movie:tmdb:550",
            mediaType = "movie",
            provider = "tmdb",
            providerId = "550",
            title = "Fight Club",
            year = 1999,
            coverResolutionState = "BROKEN_VALUE"
        )
        val rating = MediaRatingEntity(
            entity.id, 0.4, 8.0, 8.4, "note", "Completed", 1L, "general", "movies"
        )

        val domain = entity.toDomain(rating, categoryScore = 9.0)

        assertEquals(MediaType.MOVIE, domain.mediaType)
        assertEquals("tmdb", domain.provider)
        assertEquals(1999, domain.year)
        assertEquals(8.4, domain.finalScore!!, 0.0)
        assertEquals(8.0, domain.qualityScore!!, 0.0)
        assertEquals(0.4, domain.additiveScore!!, 0.0)
        assertEquals(9.0, domain.categoryScore!!, 0.0)
        assertEquals(CoverResolutionState.UNKNOWN, domain.coverResolutionState)
    }

    @Test
    fun animeAdapterKeepsAnimeOnItsExistingIdentity() {
        val anime = Anime(
            id = "mal:1",
            title = "Anime",
            popularity = 10,
            popularityRank = 2,
            finalScore = 8.5,
            baseScore = 8.0,
            additiveScore = 0.5,
            format = "TV",
            seasonYear = 2024,
            remoteCoverUrl = "https://example.test/cover.jpg"
        )

        val media = anime.toMediaItem()

        assertEquals(MediaType.ANIME, media.mediaType)
        assertNull(media.provider)
        assertNull(media.providerId)
        assertEquals(2024, media.year)
        assertEquals(8.5, media.finalScore!!, 0.0)
        assertEquals("https://example.test/cover.jpg", media.remoteCoverRef)
    }

    @Test
    fun elaborateAdapterUsesSharedCalculatorAndRepresentsNotApplicable() {
        val config = config()
        val draft = RatingDraft(
            categoryScores = mapOf("writing" to 8.0, "characters" to 6.0, "unused" to null),
            additiveScore = 0.5,
            notes = "note"
        )

        val save = buildGenericElaborateRating(
            "books", "book:test", draft, config, profileId = "general", updatedAt = 7L
        )
        val shared = ScoreCalculator.calculate(draft.categoryScores, draft.additiveScore, config)

        assertEquals(shared.qualityScore, save.rating.qualityScore)
        assertEquals(shared.finalScore, save.rating.finalScore)
        assertEquals("general", save.rating.profileId)
        assertTrue(save.scores.first { it.categoryId == "writing" }.applicable)
        assertFalse(save.scores.first { it.categoryId == "unused" }.applicable)
    }

    @Test
    fun elaborateAdapterRejectsNonDiscreteCategoryValues() {
        assertThrows(IllegalArgumentException::class.java) {
            buildGenericElaborateRating(
                packId = "mangas",
                mediaId = "manga:test",
                draft = RatingDraft(categoryScores = mapOf("writing" to 8.2)),
                config = config(),
                updatedAt = 1L
            )
        }
    }

    @Test
    fun simpleAdapterKeepsZeroAsARealCanonicalScore() {
        val rating = buildGenericSimpleRating(
            packId = "movies",
            mediaId = "movie:test",
            draft = RatingDraft(additiveScore = 0.0, notes = "zero", profileId = "visual_direction"),
            qualityScore = 0.0,
            finalScore = 0.0,
            updatedAt = 5L
        )

        assertEquals(0.0, rating.qualityScore!!, 0.0)
        assertEquals(0.0, rating.finalScore!!, 0.0)
        assertEquals("visual_direction", rating.profileId)
    }

    private fun config() = RatingSystemConfig(
        categories = listOf(
            RatingCategory("writing", "Writing", "", 50.0, "#000000", 0),
            RatingCategory("characters", "Characters", "", 50.0, "#000000", 1)
        ),
        additiveBonus = AdditiveBonus("Taste", "", 1.0, "#000000")
    )
}
