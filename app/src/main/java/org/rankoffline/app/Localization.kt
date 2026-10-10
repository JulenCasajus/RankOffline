package org.rankoffline.app

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

enum class SupportedLanguage(
    val languageTag: String,
    @StringRes val displayNameResource: Int
) {
    ENGLISH("en", R.string.language_english),
    SPANISH("es", R.string.language_spanish),
    GERMAN("de", R.string.language_german),
    FRENCH("fr", R.string.language_french),
    RUSSIAN("ru", R.string.language_russian),
    JAPANESE("ja", R.string.language_japanese),
    SIMPLIFIED_CHINESE("zh-CN", R.string.language_simplified_chinese),
    BASQUE("eu", R.string.language_basque),
    ITALIAN("it", R.string.language_italian);

    companion object {
        val DEFAULT = ENGLISH

        fun fromLanguageTag(value: String?): SupportedLanguage =
            entries.firstOrNull { it.languageTag.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}

internal fun interface AppLocaleController {
    fun apply(language: SupportedLanguage)
}

internal class AndroidXAppLocaleController : AppLocaleController {
    override fun apply(language: SupportedLanguage) {
        val requested = LocaleListCompat.forLanguageTags(language.languageTag)
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != requested.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(requested)
        }
    }
}
