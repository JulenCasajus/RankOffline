package org.rankoffline.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingRulesTest {
    private val definition = TestFixtures.ratingDefinition()

    @Test fun displayedScoresTruncateToTwoLocalizedDecimals() {
        assertEquals("7.60", formatScoreForDisplay(7.600000005, Locale.US))
        assertEquals("7.60", formatScoreForDisplay(7.609, Locale.US))
        assertEquals("7,60", formatScoreForDisplay(7.609, Locale.GERMANY))
    }

    @Test fun editedDraftCanBeSavedAcrossActivityRecreation() {
        val draft = RatingDraft(
            categoryScores = mapOf("writing" to 8.5, "visuals" to null),
            additiveScore = 0.4, notes = "Unsent", profileId = "general"
        )
        val bytes = ByteArrayOutputStream().also { stream ->
            ObjectOutputStream(stream).use { it.writeObject(draft) }
        }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.readObject() as RatingDraft
        }
        assertEquals(draft, restored)
    }

    @Test fun weightedScoreRenormalizesOnlyApplicableCategories() {
        val result = ScoreCalculator.calculate(mapOf("writing" to 8.0, "visuals" to null), 0.5,
            definition.config)
        assertEquals(8.0, result.qualityScore!!, 0.0)
        assertEquals(8.5, result.finalScore!!, 0.0)
    }

    @Test fun zeroIsAValidScoreButNoApplicableCategoriesIsNotRated() {
        assertEquals(0.0, ScoreCalculator.calculate(mapOf("writing" to 0.0), 0.0,
            definition.config).qualityScore!!, 0.0)
        assertNull(ScoreCalculator.calculate(emptyMap(), 1.0, definition.config).finalScore)
    }

    @Test fun nonFiniteAndOutOfRangeInputsCannotProduceScores() {
        assertNull(ScoreCalculator.calculate(mapOf("writing" to Double.NaN), 0.0,
            definition.config).finalScore)
        assertNull(ScoreCalculator.calculate(mapOf("writing" to 11.0), 0.0,
            definition.config).finalScore)
    }

    @Test fun bonusIsBoundedAndFinalScoreIsCapped() {
        val result = ScoreCalculator.calculate(mapOf("writing" to 10.0), 99.0, definition.config)
        assertEquals(10.0, result.finalScore!!, 0.0)
        assertEquals(10.0, finalScoreFromQuality(9.5, 1.0)!!, 0.0)
    }

    @Test fun ratingBonusAndWeightScalesAreExact() {
        assertEquals(21, discreteValues(0.0, 10.0, 0.5).size)
        assertEquals(11, discreteValues(0.0, 1.0, 0.1).size)
        assertEquals(21, discreteValues(0.0, 100.0, 5.0).size)
        assertEquals(7.5, snapRatingValue(7.49)!!, 0.0)
    }

    @Test fun directEntryAcceptsTwoDecimalsAndBothSeparators() {
        assertEquals(7.25, parseDirectRatingValue("7.25")!!, 0.0)
        assertEquals(7.25, parseDirectRatingValue("7,25")!!, 0.0)
        assertNull(parseDirectRatingValue("7.251"))
        assertNull(parseDirectRatingValue("11"))
    }

    @Test fun profileReconciliationNeverIntroducesUnknownCategoryIds() {
        val bad = RatingProfilesConfig(listOf(RatingProfile("general", "", mapOf("unknown" to 80.0))))
        val reconciled = reconcileProfiles(bad, definition.config.categories, definition.profiles)
        assertEquals(setOf("writing", "visuals"), reconciled.profiles.single().weights.keys)
        assertTrue(reconciled.profiles.single().weights.values.sum() > 0.0)
    }

    @Test fun invalidRatingConfigurationsAreRejected() {
        val duplicate = definition.config.copy(categories = listOf(
            definition.config.categories.first(), definition.config.categories.first().copy(order = 1)
        ))
        assertThrows(IllegalArgumentException::class.java) {
            validateRatingConfiguration(duplicate, definition.profiles)
        }
    }

    @Test fun groupDerivationUsesOnlyRatedMembersFromOnePack() {
        val ratings = listOf(
            MediaRatingEntity(11, "a", 0.5, 8.0, 8.5, "", "", 1),
            MediaRatingEntity(11, "b", 0.0, 6.0, 6.0, "", "", 1),
            MediaRatingEntity(11, "c", 0.0, null, null, "", "", 1)
        )
        val scores = listOf(
            MediaRatingScoreEntity(11, "a", "writing", 8.0, true),
            MediaRatingScoreEntity(11, "b", "writing", 6.0, true)
        )
        val derived = deriveMediaGroupRating(ratings, scores)
        assertEquals(2, derived.ratedCount)
        assertEquals(7.25, derived.finalScore!!, 0.0)
        assertEquals(7.0, derived.categoryScores.getValue("writing"), 0.0)
    }

    @Test fun groupDerivationRejectsCrossPackInputs() {
        val ratings = listOf(
            MediaRatingEntity(11, "a", 0.0, 8.0, 8.0, "", "", 1),
            MediaRatingEntity(12, "b", 0.0, 8.0, 8.0, "", "", 1)
        )
        assertThrows(IllegalArgumentException::class.java) { deriveMediaGroupRating(ratings, emptyList()) }
    }
}
