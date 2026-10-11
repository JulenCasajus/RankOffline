package org.rankoffline.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDefaultsTest {
    @Test
    fun imageDisplayDefaultsOffButPreservesStoredValues() {
        assertFalse(AppSettingsState().showImages)
        assertFalse(resolveShowImages(null))
        assertTrue(resolveShowImages(true))
        assertFalse(resolveShowImages(false))
    }

    @Test
    fun simpleRatingIsDefaultButExplicitExistingModeIsPreserved() {
        assertFalse(AppSettingsState().elaborateRatingEnabled)
        assertFalse(resolveElaborateRatingEnabled(null))
        assertTrue(resolveElaborateRatingEnabled(true))
        assertFalse(resolveElaborateRatingEnabled(false))
    }

    @Test
    fun officialDefaultCategoriesHaveStableOrderWeightsAndContent() {
        val categories = DefaultSettings.defaultRatingCategories

        assertEquals(listOf("writing", "characters", "visuals", "worldbuilding", "audio"), categories.map { it.id })
        assertEquals(listOf(35.0, 20.0, 20.0, 15.0, 10.0), categories.map { it.weight })
        assertEquals(100.0, categories.sumOf { it.weight }, 0.0)
        assertEquals((0..4).toList(), categories.map { it.order })
        assertEquals(listOf("#19D5E5", "#E04BCF", "#FFAA55", "#62D6C8", "#7EC7FF"), categories.map { it.color })
        assertTrue(categories.all { it.active })
        assertEquals("Plot, themes, pacing, highlights, beginning, ending", categories[0].description)
        assertEquals("Main characters, supporting characters, antagonist, development, depth, charisma, dynamics", categories[1].description)
        assertEquals("Animation, art style, cinematography, backgrounds, effects, character design", categories[2].description)
        assertEquals("Setting, lore, power system", categories[3].description)
        assertEquals("OST, openings/endings, sound effects", categories[4].description)
    }

    @Test
    fun existingActiveCustomCategoriesRemainAndOnlyLegacyInactiveEntriesAreRemoved() {
        val custom = RatingCategory("custom", "Mine", "Keep", 33.0, "#123456", 4)
        val inactive = RatingCategory("old", "Old", "Deleted", 10.0, "#FFFFFF", 5, active = false)
        val persisted = RatingSystemConfig(listOf(custom, inactive), null)
        val resolved = resolveRatingConfig(persisted)
        val normalized = normalizeLegacyInactiveCategories(resolved)

        assertEquals(listOf(custom), normalized.config.categories)
        assertEquals(listOf("old"), normalized.removedCategoryIds)
        assertEquals(33.0, normalized.config.categories.first().weight, 0.0)
        assertEquals(DefaultSettings.defaultRatingConfig, resolveRatingConfig(null))
    }

    @Test
    fun languageDefaultsToEnglishAndInvalidTagsFallBackToEnglish() {
        assertSame(SupportedLanguage.ENGLISH, AppSettingsState().language)
        assertSame(SupportedLanguage.ENGLISH, SupportedLanguage.fromLanguageTag(null))
        assertSame(SupportedLanguage.ENGLISH, SupportedLanguage.fromLanguageTag("invalid"))
        assertSame(SupportedLanguage.SIMPLIFIED_CHINESE, SupportedLanguage.fromLanguageTag("zh-CN"))
    }
}
