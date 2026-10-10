package org.rankoffline.app

import org.junit.Assert.assertFalse
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
    fun elaborateRatingDefaultsOnButPreservesStoredValues() {
        assertTrue(AppSettingsState().elaborateRatingEnabled)
        assertTrue(resolveElaborateRatingEnabled(null))
        assertTrue(resolveElaborateRatingEnabled(true))
        assertFalse(resolveElaborateRatingEnabled(false))
    }

    @Test
    fun languageDefaultsToEnglishAndInvalidTagsFallBackToEnglish() {
        assertSame(SupportedLanguage.ENGLISH, AppSettingsState().language)
        assertSame(SupportedLanguage.ENGLISH, SupportedLanguage.fromLanguageTag(null))
        assertSame(SupportedLanguage.ENGLISH, SupportedLanguage.fromLanguageTag("invalid"))
        assertSame(SupportedLanguage.SIMPLIFIED_CHINESE, SupportedLanguage.fromLanguageTag("zh-CN"))
    }
}
