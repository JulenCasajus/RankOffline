package org.rankoffline.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemePaletteTest {
    @Test
    fun currentMidnightPaletteRemainsTheDefaultAndInvalidValuesAreSafe() {
        assertSame(AppThemePalette.MIDNIGHT_NEON, AppSettingsState().themePalette)
        assertSame(AppThemePalette.MIDNIGHT_NEON, AppThemePalette.fromPersistedValue(null))
        assertSame(AppThemePalette.MIDNIGHT_NEON, AppThemePalette.fromPersistedValue("unknown"))
    }

    @Test
    fun allSevenPalettesRoundTripAndHaveDistinctColorSignatures() {
        assertEquals(7, AppThemePalette.entries.size)
        AppThemePalette.entries.forEach { palette ->
            assertSame(palette, AppThemePalette.fromPersistedValue(palette.persistedValue))
        }

        val signatures = AppThemePalette.entries.map { palette ->
            palette.preview().let { listOf(it.background, it.surface, it.primary, it.secondary) }
        }
        assertEquals(signatures.size, signatures.toSet().size)
        assertEquals(AppThemePalette.entries.size, AppThemePalette.entries.map { it.displayNameResource }.toSet().size)
    }

    @Test
    fun cherryBlossomIsAWhiteRedAndPinkPalette() {
        val preview = AppThemePalette.CHERRY_BLOSSOM.preview()

        assertEquals(Color(0xFFFCE5EC), preview.background)
        assertEquals(Color(0xFFFFCEE1), preview.surface)
        assertEquals(Color(0xFFB92344), preview.primary)
        assertEquals(Color(0xFF551D30), preview.secondary)
        assertNotEquals(preview.primary, preview.secondary)
        assertEquals(Color.Black, AppThemePalette.CHERRY_BLOSSOM.colorScheme().onBackground)
    }

    @Test
    fun pinkExplosionIsDarkFuchsiaAndDistinctFromCherryBlossom() {
        val preview = AppThemePalette.PINK_EXPLOSION.preview()

        assertEquals(Color(0xFF48052B), preview.background)
        assertEquals(Color(0xFF751150), preview.surface)
        assertEquals(Color(0xFFFF2DBF), preview.primary)
        assertNotEquals(AppThemePalette.CHERRY_BLOSSOM.preview(), preview)
        assertEquals(Color.White, AppThemePalette.PINK_EXPLOSION.colorScheme().onBackground)
    }

    @Test
    fun goldenSunsetUsesWarmOrangeSurfaces() {
        val preview = AppThemePalette.GOLDEN_SUNSET.preview()

        assertEquals(Color(0xFF42210A), preview.background)
        assertEquals(Color(0xFF79462F), preview.surface)
        assertEquals(Color(0xFFFFCA5C), preview.primary)
        assertEquals(Color(0xFFFF61D2), preview.secondary)
        assertNotEquals(AppThemePalette.PINK_EXPLOSION.preview(), preview)
    }

    @Test
    fun semanticForegroundsAreDerivedFromTheirActualContainers() {
        val palette = customPalette("automatic", "Automatic").palette.copy(
            background = Color(0xFFF8F5F2),
            surface = Color(0xFF171717)
        )

        assertEquals(Color.Black, palette.toColorScheme().onBackground)
        assertEquals(Color.White, palette.toColorScheme().onSurface)
    }

    @Test
    fun customThemeSerializesAndResolvesThroughUnifiedSelection() {
        val custom = customPalette("my-theme", "My Theme")
        val encoded = encodeThemeWorkspace(ThemeWorkspace(customPalettes = listOf(custom)))
        val decoded = decodeThemeWorkspace(encoded)
        val selection = ThemeSelection.fromPersistedValue("custom:my-theme", decoded.customPalettes)

        assertEquals(listOf(custom), decoded.customPalettes)
        assertEquals(custom.palette, selection.resolve(decoded.customPalettes, decoded.builtInOverrides))
        assertFalse(encoded.contains("\"tertiary\""))
        assertEquals(custom.palette.secondary, custom.palette.toColorScheme().tertiary)
    }

    @Test
    fun legacyCustomThemeWithTertiaryStillDeserializesAndDiscardsIt() {
        val custom = customPalette("legacy-theme", "Legacy Theme")
        val currentJson = encodeThemeWorkspace(ThemeWorkspace(customPalettes = listOf(custom)))
        val legacyJson = currentJson.replace(
            "\"secondary\":4294928025",
            "\"secondary\":4294928025,\"tertiary\":4289449455,\"mode\":\"DARK\""
        )

        val decoded = decodeThemeWorkspace(legacyJson).customPalettes.single()
        val rewritten = encodeThemeWorkspace(ThemeWorkspace(customPalettes = listOf(decoded)))

        assertEquals(custom, decoded)
        assertFalse(rewritten.contains("\"tertiary\""))
    }

    @Test
    fun dynamicContentColorsRemainReadableOnLightAndDarkBackgrounds() {
        assertEquals(Color.Black, bestContentColor(Color(0xFFF4F4F4)))
        assertEquals(Color.White, bestContentColor(Color(0xFF171717)))
        assertEquals(Color.Black, bestContentColor(scoreColor(9.5)))
        assertEquals(Color.Black, bestContentColor(scoreColor(1.0)))
    }

    @Test
    fun lightSliderInactiveTrackIsSoftAndLightWhileDarkTrackStaysDark() {
        val lightTrack = sliderInactiveTrackColor(
            background = Color(0xFFF8F8F8),
            surface = Color(0xFFF0F0F0),
            onSurface = Color.Black
        )
        val darkTrack = sliderInactiveTrackColor(
            background = Color(0xFF080808),
            surface = Color(0xFF181818),
            onSurface = Color.White
        )

        assertTrue(lightTrack.luminance() > 0.5f)
        assertTrue(darkTrack.luminance() < 0.5f)
        assertNotEquals(Color.White, lightTrack)
    }

    @Test
    fun editingCustomThemePreservesItsStableId() {
        val original = customPalette("stable-id", "Original")
        val edited = original.copy(name = "Edited", palette = original.palette.copy(primary = Color.Red))

        val result = listOf(original).upsert(edited)

        assertEquals(1, result.size)
        assertEquals("stable-id", result.single().id)
        assertEquals("Edited", result.single().name)
        assertEquals(Color.Red, result.single().palette.primary)
    }

    @Test
    fun deletingActiveCustomThemeFallsBackToAvailableBuiltIn() {
        val custom = customPalette("active", "Active")
        val state = AppSettingsState(
            themeSelection = ThemeSelection.Custom(custom.id),
            customPalettes = listOf(custom)
        )

        val result = state.withCustomPaletteDeleted(custom.id)

        assertTrue(result.customPalettes.isEmpty())
        assertEquals(
            ThemeSelection.BuiltIn(AppThemePalette.MIDNIGHT_NEON),
            result.themeSelection
        )
    }

    @Test
    fun invalidCustomSelectionFallsBackSafely() {
        assertEquals(
            ThemeSelection.BuiltIn(AppThemePalette.MIDNIGHT_NEON),
            ThemeSelection.fromPersistedValue("custom:missing", emptyList())
        )
    }

    @Test
    fun builtInEditsAndDeletionsPersistWithoutMutatingOriginalPalette() {
        val source = AppThemePalette.DEEP_OCEAN
        val original = source.palette()
        val edited = customPalette(source.persistedValue, "Edited Ocean").copy(
            palette = original.copy(primary = Color.Magenta)
        )
        val editedState = AppSettingsState(
            themeSelection = ThemeSelection.BuiltIn(source)
        ).withBuiltInPaletteSaved(source, edited)
        val hiddenState = editedState.withBuiltInPaletteDeleted(source)
        val restored = hiddenState.copy(
            builtInPaletteOverrides = emptyMap(),
            hiddenBuiltInPalettes = emptySet()
        )
        val roundTrip = decodeThemeWorkspace(encodeThemeWorkspace(editedState.themeWorkspace()))

        assertEquals(Color.Magenta, editedState.activePalette.primary)
        assertEquals(original, source.palette())
        assertEquals("Edited Ocean", roundTrip.builtInOverrides.getValue(source).name)
        assertTrue(source in hiddenState.hiddenBuiltInPalettes)
        assertFalse(source in restored.hiddenBuiltInPalettes)
        assertEquals(original, ThemeSelection.BuiltIn(source).resolve(
            restored.customPalettes,
            restored.builtInPaletteOverrides
        ))
    }

    @Test
    fun resettingBuiltInsRestoresOriginalsWithoutRemovingCustomThemes() {
        val source = AppThemePalette.CHERRY_BLOSSOM
        val custom = customPalette("personal", "Personal")
        val modified = customPalette(source.persistedValue, "Modified default")
        val state = AppSettingsState(
            themeSelection = ThemeSelection.Custom(custom.id),
            customPalettes = listOf(custom),
            builtInPaletteOverrides = mapOf(source to modified),
            hiddenBuiltInPalettes = setOf(AppThemePalette.DEEP_OCEAN)
        )

        val reset = state.withBuiltInPalettesReset()

        assertTrue(reset.builtInPaletteOverrides.isEmpty())
        assertTrue(reset.hiddenBuiltInPalettes.isEmpty())
        assertEquals(listOf(custom), reset.customPalettes)
        assertEquals(ThemeSelection.Custom(custom.id), reset.themeSelection)
        assertEquals(source.palette(), ThemeSelection.BuiltIn(source).resolve(
            reset.customPalettes,
            reset.builtInPaletteOverrides
        ))
    }

    private fun customPalette(id: String, name: String) = CustomAppPalette(
        id = id,
        name = name,
        palette = AppPalette(
            background = Color(0xFF101010),
            surface = Color(0xFF202020),
            primary = Color(0xFFFF00AA),
            secondary = Color(0xFFFF6699)
        )
    )
}
