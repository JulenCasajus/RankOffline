package org.rankoffline.app

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalSettingsImportTest {
    @Test
    fun initialLoadCannotOverwriteConcurrentPreferenceUpdate() = runBlocking {
        val context = IsolatedTestContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "settings-initial-load-race"
        )
        val icons = object : AppIconAliasController {
            override fun activate(icon: AppIcon, previousIcon: AppIcon) = Unit
            override fun activeIconOrNull(): AppIcon = AppIcon.DARK
        }
        try {
            val repository = SettingsRepository(context, icons, AppLocaleController { })
            val loading = async { repository.loadInitialState() }
            yield()
            val updating = async { repository.updateActiveMediaType(MediaType.BOOK) }
            loading.await()
            updating.await()

            assertEquals(MediaType.BOOK, repository.state.value.activeMediaType)
            val recreated = SettingsRepository(context, icons, AppLocaleController { })
            recreated.loadInitialState()
            assertEquals(MediaType.BOOK, recreated.state.value.activeMediaType)
        } finally {
            context.delete()
        }
    }

    @Test
    fun importedPackSettingsAndThemesSurviveRepositoryRecreation() = runBlocking {
        val context = IsolatedTestContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "personal-settings-import"
        )
        val icons = object : AppIconAliasController {
            override fun activate(icon: AppIcon, previousIcon: AppIcon) = Unit
            override fun activeIconOrNull(): AppIcon = AppIcon.DARK
        }
        val locale = AppLocaleController { }
        val custom = CustomAppPalette(
            "backup_theme",
            "Backup theme",
            AppPalette(Color.Black, Color.DarkGray, Color.Magenta, Color.Cyan)
        )
        try {
            val repository = SettingsRepository(context, icons, locale)
            repository.loadInitialState()
            val importedBook = DefaultSettings.mediaSettings(MediaType.BOOK).copy(
                showImages = true,
                stickyScore = false
            )
            repository.applyImportedPersonalSettings(
                imported = mapOf(MediaType.BOOK to importedBook),
                importedInitializedPacks = setOf(MediaType.BOOK),
                importedWorkspace = ThemeWorkspace(customPalettes = listOf(custom)),
                importedSortOrders = mapOf(MediaType.BOOK to CatalogSortOrder.TITLE_DESC),
                importedAllViewSettings = AllViewSettings(
                    showImages = false,
                    stickyScore = false,
                    catalogSortOrder = CatalogSortOrder.TITLE_ASC
                ),
                importedThemeSelection = ThemeSelection.Custom(custom.id).persistedValue,
                replace = true
            )

            val recreated = SettingsRepository(context, icons, locale)
            recreated.loadInitialState()
            val state = recreated.state.value
            assertTrue(state.mediaSettings.getValue(MediaType.BOOK).showImages)
            assertFalse(state.mediaSettings.getValue(MediaType.BOOK).stickyScore)
            assertEquals(CatalogSortOrder.TITLE_DESC, state.catalogSortOrders.getValue(MediaType.BOOK))
            assertEquals(CatalogSortOrder.TITLE_ASC, state.allViewSettings.catalogSortOrder)
            assertEquals(ThemeSelection.Custom(custom.id), state.themeSelection)
            assertEquals(setOf(MediaType.BOOK), recreated.initializedPackSettingsSnapshot())
        } finally {
            context.delete()
        }
    }

    @Test
    fun scopedReplacePreservesSettingsAndThemesOwnedOutsideTheSelectedPack() = runBlocking {
        val context = IsolatedTestContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "personal-settings-scoped-import"
        )
        val icons = object : AppIconAliasController {
            override fun activate(icon: AppIcon, previousIcon: AppIcon) = Unit
            override fun activeIconOrNull(): AppIcon = AppIcon.DARK
        }
        val custom = CustomAppPalette(
            "keep_theme",
            "Keep theme",
            AppPalette(Color.Black, Color.DarkGray, Color.Magenta, Color.Cyan)
        )
        try {
            val repository = SettingsRepository(context, icons, AppLocaleController { })
            repository.loadInitialState()
            val anime = DefaultSettings.mediaSettings(MediaType.ANIME).copy(
                showImages = false,
                stickyScore = false
            )
            repository.applyImportedPersonalSettings(
                imported = mapOf(MediaType.ANIME to anime),
                importedInitializedPacks = setOf(MediaType.ANIME),
                importedWorkspace = ThemeWorkspace(customPalettes = listOf(custom)),
                importedSortOrders = mapOf(MediaType.ANIME to CatalogSortOrder.TITLE_DESC),
                importedAllViewSettings = AllViewSettings(),
                importedThemeSelection = AppThemePalette.MIDNIGHT_NEON.persistedValue,
                replace = false
            )
            val books = DefaultSettings.mediaSettings(MediaType.BOOK).copy(showImages = true)
            repository.applyImportedPersonalSettings(
                imported = mapOf(MediaType.BOOK to books),
                importedInitializedPacks = setOf(MediaType.BOOK),
                importedWorkspace = ThemeWorkspace(),
                importedSortOrders = mapOf(MediaType.BOOK to CatalogSortOrder.TITLE_ASC),
                importedAllViewSettings = AllViewSettings(),
                importedThemeSelection = AppThemePalette.MIDNIGHT_NEON.persistedValue,
                replace = true,
                scopeMediaTypes = setOf(MediaType.BOOK),
                includeGlobalSettings = false
            )

            assertEquals(anime, repository.state.value.mediaSettings.getValue(MediaType.ANIME))
            assertTrue(repository.state.value.customPalettes.any { it.id == custom.id })
            assertEquals(books, repository.state.value.mediaSettings.getValue(MediaType.BOOK))
        } finally {
            context.delete()
        }
    }
}
