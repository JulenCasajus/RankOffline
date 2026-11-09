package org.rankoffline.app

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Languages translated by the APK. Downloadable packs may support additional languages. */
enum class SupportedLanguage(
    val languageTag: String,
    @StringRes val displayNameResource: Int
) {
    GERMAN("de", R.string.language_german),
    ENGLISH("en", R.string.language_english),
    SPANISH("es", R.string.language_spanish),
    BASQUE("eu", R.string.language_basque),
    FRENCH("fr", R.string.language_french),
    ITALIAN("it", R.string.language_italian),
    RUSSIAN("ru", R.string.language_russian),
    JAPANESE("ja", R.string.language_japanese),
    SIMPLIFIED_CHINESE("zh-CN", R.string.language_simplified_chinese);

    companion object {
        val DEFAULT = ENGLISH

        /** A missing preference resolves to a supported device language. */
        fun fromLanguageTag(value: String?): SupportedLanguage {
            if (value == null) {
                return fromSystemLocale(currentSystemLocale())
            }
            return entries.firstOrNull {
                it.languageTag.equals(value, ignoreCase = true)
            } ?: ENGLISH
        }

        private fun currentSystemLocale(): Locale {
            val locales = Resources.getSystem().configuration.locales
            return if (locales.isEmpty) Locale.ENGLISH else locales[0]
        }

        private fun fromSystemLocale(locale: Locale): SupportedLanguage =
            when (locale.language.lowercase(Locale.ROOT)) {
                "en" -> ENGLISH
                "es" -> SPANISH
                "de" -> GERMAN
                "fr" -> FRENCH
                "ru" -> RUSSIAN
                "ja" -> JAPANESE
                "eu" -> BASQUE
                "it" -> ITALIAN
                "zh" -> if (
                    locale.script.equals("Hans", ignoreCase = true) ||
                    locale.country.equals("CN", ignoreCase = true) ||
                    locale.country.equals("SG", ignoreCase = true)
                ) SIMPLIFIED_CHINESE else ENGLISH
                else -> ENGLISH
            }
    }
}

/** Allows locale changes to be replaced by a fake in unit tests. */
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
