package org.rankoffline.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconSettingsTest {
    @Test
    fun defaultIsDark() {
        assertSame(AppIcon.DARK, AppSettingsState().appIcon)
        assertSame(AppIcon.DARK, AppIcon.fromPersistedValue(null))
        assertEquals(R.string.icon_default, AppIcon.DARK.displayNameResource)
        assertEquals("DARK", AppIcon.DARK.persistedValue)
    }

    @Test
    fun everyIconRoundTripsThroughItsPersistedValue() {
        AppIcon.entries.forEach { icon ->
            assertSame(icon, AppIcon.fromPersistedValue(icon.persistedValue))
        }
    }

    @Test
    fun invalidPersistedValueFallsBackToDark() {
        assertSame(AppIcon.DARK, AppIcon.fromPersistedValue("PURPLE"))
        assertSame(AppIcon.DARK, AppIcon.fromPersistedValue(""))
    }

    @Test
    fun selectingEveryAlternativePersistsThenActivatesThatIcon() = runBlocking {
        listOf(AppIcon.BLUE, AppIcon.RED, AppIcon.GREEN, AppIcon.YELLOW).forEach { selected ->
            val events = mutableListOf<String>()

            persistAndActivateAppIcon(
                icon = selected,
                previousIcon = AppIcon.DARK,
                persistSelection = { events += "persist:${it.persistedValue}" },
                activateAlias = { icon, previous ->
                    events += "activate:${icon.persistedValue}:after:${previous.persistedValue}"
                }
            )

            assertEquals(
                listOf("persist:${selected.persistedValue}", "activate:${selected.persistedValue}:after:DARK"),
                events
            )
        }
    }

    @Test
    fun everyEnumMapsToOneKnownUniqueAlias() {
        val aliases = AppIcon.entries.map { it.componentClassName("org.rankoffline.app") }

        assertEquals(
            listOf(
                "org.rankoffline.app.MainActivityDark",
                "org.rankoffline.app.MainActivityBlue",
                "org.rankoffline.app.MainActivityRed",
                "org.rankoffline.app.MainActivityGreen",
                "org.rankoffline.app.MainActivityYellow"
            ),
            aliases
        )
        assertEquals(AppIcon.entries.size, aliases.toSet().size)
        assertTrue(aliases.none { it.isBlank() })
    }
}
