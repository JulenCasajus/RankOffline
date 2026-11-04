package org.rankoffline.app

import androidx.compose.ui.graphics.Color
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeAndLocalizationTest {
    @Test fun primaryRgbColorsRoundTripThroughHsv() {
        listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())
            .forEach { color -> assertEquals(color, HsvColor.fromArgb(color).toArgb()) }
    }

    @Test fun hsvNormalizesHueAndBoundsChannels() {
        val normalized = HsvColor(-60f, 2f, -1f).normalized()
        assertEquals(300f, normalized.hue, 0f)
        assertEquals(1f, normalized.saturation, 0f)
        assertEquals(0f, normalized.value, 0f)
    }

    @Test fun hexParsingIsStrictAndCanonical() {
        assertEquals("#AABBCC", normalizeHexColor(" #aabbcc "))
        assertNull(normalizeHexColor("#abcd"))
        assertNull(normalizeHexColor("AABBCC"))
    }

    @Test fun everyBuiltInPaletteHasUniqueStableIdentity() {
        assertEquals(AppThemePalette.entries.size,
            AppThemePalette.entries.map { it.persistedValue }.distinct().size)
        assertEquals(AppThemePalette.MIDNIGHT_NEON, AppThemePalette.fromPersistedValue("invalid"))
    }

    @Test fun foregroundSelectionUsesActualContainerContrast() {
        assertEquals(Color.White, bestContentColor(Color.Black))
        assertEquals(Color.Black, bestContentColor(Color.White))
        AppThemePalette.entries.forEach { palette ->
            val colors = palette.palette()
            assertTrue(bestContentColor(colors.primary) == Color.Black ||
                bestContentColor(colors.primary) == Color.White)
        }
    }

    @Test fun customThemeSelectionFallsBackWithoutMutatingBuiltIns() {
        val custom = CustomAppPalette("custom", "Custom",
            AppPalette(Color.Black, Color.DarkGray, Color.Red, Color.Blue))
        assertEquals(custom.palette, ThemeSelection.Custom("custom").resolve(listOf(custom)))
        assertEquals(AppThemePalette.MIDNIGHT_NEON.palette(),
            ThemeSelection.Custom("missing").resolve(emptyList()))
    }

    @Test fun allNineLocaleFilesAreValidAndContainCoreNavigationKeys() {
        val root = File("src/res")
        val directories = listOf("values", "values-de", "values-es", "values-eu", "values-fr",
            "values-it", "values-ja", "values-ru", "values-zh-rCN")
        val keys = directories.associateWith { directory ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(root, "$directory/strings.xml"))
            val nodes = document.documentElement.childNodes
            buildSet {
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index)
                    val name = node.attributes?.getNamedItem("name")?.nodeValue
                    val nonTranslatable = node.attributes?.getNamedItem("translatable")?.nodeValue == "false"
                    if (name != null && !nonTranslatable) add(name)
                }
            }
        }
        val core = setOf("nav_search", "nav_ranking", "nav_settings", "settings_title",
            "language_title", "language_english", "language_spanish", "action_cancel")
        keys.forEach { (directory, values) ->
            assertTrue("$directory misses ${core - values}", values.containsAll(core))
        }
        assertEquals(9, keys.size)
    }
}
