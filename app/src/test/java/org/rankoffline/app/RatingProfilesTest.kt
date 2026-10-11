package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingProfilesTest {
    private val categoryIds = listOf("writing", "characters", "visuals", "worldbuilding", "audio")

    @Test
    fun officialProfilesHaveStableIdsAndExactWeights() {
        val profiles = DefaultSettings.defaultProfiles().profiles

        assertEquals(
            listOf("general", "narrative", "character_driven", "visual_action", "world_adventure", "atmospheric"),
            profiles.map { it.id }
        )
        assertEquals(listOf(35.0, 20.0, 20.0, 15.0, 10.0), weights(profiles[0]))
        assertEquals(listOf(45.0, 25.0, 10.0, 10.0, 10.0), weights(profiles[1]))
        assertEquals(listOf(25.0, 40.0, 15.0, 10.0, 10.0), weights(profiles[2]))
        assertEquals(listOf(20.0, 15.0, 40.0, 15.0, 10.0), weights(profiles[3]))
        assertEquals(listOf(20.0, 20.0, 15.0, 35.0, 10.0), weights(profiles[4]))
        assertEquals(listOf(25.0, 20.0, 20.0, 15.0, 20.0), weights(profiles[5]))
    }

    @Test
    fun profileCrudKeepsStableIdsAndPreventsDeletingLastProfile() {
        val original = DefaultSettings.defaultProfiles()
        val created = RatingProfile("custom-id", "Custom", categoryIds.associateWith { 5.0 })
        val withCreated = original.copy(profiles = original.profiles + created)
        val renamed = withCreated.copy(profiles = withCreated.profiles.map {
            if (it.id == created.id) it.copy(name = "Renamed") else it
        })

        assertEquals("Renamed", renamed.profiles.last().name)
        assertEquals("custom-id", renamed.profiles.last().id)
        assertFalse(renamed.withProfileDeleted("custom-id").profiles.any { it.id == "custom-id" })
        val sole = RatingProfilesConfig(listOf(created))
        assertEquals(sole, sole.withProfileDeleted(created.id))
    }

    @Test
    fun categoryLifecycleUsesStableIdsAcrossEveryProfile() {
        val original = DefaultSettings.defaultProfiles()
        val added = original.withCategoryAdded("custom")
        assertTrue(added.profiles.all { it.weights["custom"] == 0.0 })

        val renamedCategory = RatingCategory("writing", "Renamed", "Changed", 1.0, "#000000", 0)
        val reconciled = reconcileProfiles(added, listOf(renamedCategory, customCategory()))
        assertTrue(reconciled.profiles.all { it.weights.containsKey("writing") })
        assertTrue(reconciled.profiles.all { it.weights.containsKey("custom") })

        val removed = reconciled.withCategoryRemoved("writing")
        assertTrue(removed.profiles.all { !it.weights.containsKey("writing") })
    }

    @Test
    fun categoryAndProfileResetsAreIndependent() {
        val customProfile = RatingProfile("mine", "Mine", mapOf("writing" to 55.0, "custom" to 45.0))
        val categories = DefaultSettings.defaultRatingCategories + customCategory()

        val categoryReset = reconcileProfiles(RatingProfilesConfig(listOf(customProfile)), DefaultSettings.defaultRatingCategories)
        assertEquals(listOf("mine"), categoryReset.profiles.map { it.id })
        assertEquals(55.0, categoryReset.profiles.single().weights["writing"]!!, 0.0)
        assertFalse(categoryReset.profiles.single().weights.containsKey("custom"))

        val profileReset = DefaultSettings.defaultProfiles(categories)
        assertEquals(6, profileReset.profiles.size)
        assertTrue(profileReset.profiles.all { it.weights["custom"] == 0.0 })
        assertTrue(categories.any { it.id == "custom" })
    }

    @Test
    fun customOnlyCategoriesProduceUsableFiniteDefaultProfiles() {
        val category = customCategory()
        DefaultSettings.defaultProfiles(listOf(category)).profiles.forEach { profile ->
            val config = RatingSystemConfig(listOf(category), null).withProfile(profile)
            val result = ScoreCalculator.calculate(mapOf(category.id to 8.0), 0.0, config)
            assertTrue(result.finalScore?.isFinite() == true)
        }
    }

    @Test
    fun selectingDifferentProfilesChangesCalculationWithoutMutatingSavedScore() {
        val profiles = DefaultSettings.defaultProfiles()
        val scores = mapOf(
            "writing" to 10.0,
            "characters" to 0.0,
            "visuals" to 0.0,
            "worldbuilding" to 0.0,
            "audio" to 0.0
        )
        val general = ScoreCalculator.calculate(scores, 0.0, DefaultSettings.defaultRatingConfig.withProfile(profiles.resolveProfile("general")))
        val narrative = ScoreCalculator.calculate(scores, 0.0, DefaultSettings.defaultRatingConfig.withProfile(profiles.resolveProfile("narrative")))
        val persistedFinalScore = 8.5

        assertEquals(3.5, general.finalScore!!, 0.0)
        assertEquals(4.5, narrative.finalScore!!, 0.0)
        assertNotEquals(general.finalScore, narrative.finalScore)
        assertEquals(8.5, persistedFinalScore, 0.0)
    }

    @Test
    fun missingProfileFallsBackToGeneralThenFirst() {
        val defaults = DefaultSettings.defaultProfiles()
        assertEquals("general", defaults.resolveProfile("deleted").id)

        val only = RatingProfile("only", "Only", categoryIds.associateWith { 20.0 })
        assertEquals("only", RatingProfilesConfig(listOf(only)).resolveProfile("deleted").id)
    }

    @Test
    fun existingCategoryWeightsBecomeInitialGeneralWithoutReplacingCustomization() {
        val custom = customCategory().copy(weight = 65.0)
        val categories = listOf(custom)
        val initial = DefaultSettings.initialProfilesForExistingCategories(categories)

        assertEquals(65.0, initial.resolveProfile("general").weights.getValue("custom"), 0.0)
        assertEquals(listOf("custom"), initial.resolveProfile("general").weights.keys.toList())
    }

    private fun weights(profile: RatingProfile) = categoryIds.map { profile.weights.getValue(it) }

    private fun customCategory() = RatingCategory("custom", "Custom", "Custom description", 25.0, "#123456", 9)
}
