package org.rankoffline.app

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalDataBackupRecoveryTest {
    private lateinit var context: IsolatedTestContext
    private lateinit var database: AnimeDatabase

    @Before
    fun setUp() {
        context = IsolatedTestContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "personal-backup-recovery"
        )
        database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
        context.delete()
    }

    @Test
    fun interruptionBeforeRoomCommitRollsSettingsBack() = runBlocking {
        val settings = settingsRepository().also { it.loadInitialState() }
        database.personalDataDao().restore(restoreData("local", 10L), replace = true)
        val manager = manager(settings) { checkpoint ->
            if (checkpoint == PersonalRestoreCheckpoint.SETTINGS_APPLIED) throw SimulatedDeath()
        }

        try {
            manager.import(preview("incoming", 20L, showImages = true), PersonalDataImportMode.REPLACE)
        } catch (_: SimulatedDeath) {
            // Simulates process death: normal exception cleanup deliberately does not run.
        }
        assertTrue(settings.state.value.mediaSettings.getValue(MediaType.BOOK).showImages)

        val recoveredSettings = settingsRepository().also { it.loadInitialState() }
        val recovered = manager(recoveredSettings).recoverInterruptedImport()

        assertFalse(recovered)
        assertFalse(recoveredSettings.state.value.mediaSettings.getValue(MediaType.BOOK).showImages)
        assertEquals("local", database.personalDataDao().allRatings().single().notes)
    }

    @Test
    fun interruptionAfterRoomCommitReplaysImportedSettingsIdempotently() = runBlocking {
        val settings = settingsRepository().also { it.loadInitialState() }
        database.personalDataDao().restore(restoreData("local", 10L), replace = true)
        val manager = manager(settings) { checkpoint ->
            if (checkpoint == PersonalRestoreCheckpoint.ROOM_COMMITTED) throw SimulatedDeath()
        }

        try {
            manager.import(preview("incoming", 20L, showImages = true), PersonalDataImportMode.REPLACE)
        } catch (_: SimulatedDeath) {
            // Simulates process death after Room's transaction committed.
        }
        val recoveredSettings = settingsRepository().also { it.loadInitialState() }
        val recoveredManager = manager(recoveredSettings)

        assertTrue(recoveredManager.recoverInterruptedImport())
        assertTrue(recoveredSettings.state.value.mediaSettings.getValue(MediaType.BOOK).showImages)
        assertEquals("incoming", database.personalDataDao().allRatings().single().notes)
        assertFalse(recoveredManager.recoverInterruptedImport())
        assertEquals(1, database.personalDataDao().allRatings().size)
    }

    @Test
    fun cancellationBeforeRoomCommitRollsSettingsBackAndPropagates() = runBlocking {
        val settings = settingsRepository().also {
            it.loadInitialState()
            it.updateActiveMediaType(MediaType.BOOK)
            it.updateShowImages(false)
        }
        database.personalDataDao().restore(restoreData("local", 10L), replace = true)
        val manager = manager(settings) { checkpoint ->
            if (checkpoint == PersonalRestoreCheckpoint.SETTINGS_APPLIED) {
                throw CancellationException("test cancellation")
            }
        }

        var cancelled = false
        try {
            manager.import(preview("incoming", 20L, showImages = true), PersonalDataImportMode.REPLACE)
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertFalse(settings.state.value.mediaSettings.getValue(MediaType.BOOK).showImages)
        assertEquals("local", database.personalDataDao().allRatings().single().notes)
        assertFalse(manager.recoverInterruptedImport())
    }

    @Test
    fun truncatedPrimaryJournalRecoversFromRedundantCopy() = runBlocking {
        val settings = settingsRepository().also { it.loadInitialState() }
        val manager = manager(settings) { checkpoint ->
            if (checkpoint == PersonalRestoreCheckpoint.ROOM_COMMITTED) throw SimulatedDeath()
        }
        runCatching {
            manager.import(preview("incoming", 20L, showImages = true), PersonalDataImportMode.REPLACE)
        }
        context.filesDir.resolve("personal-restore-journal-v1.json").writeText("{")

        val recovered = manager(settingsRepository().also { it.loadInitialState() })
            .recoverInterruptedImport()

        assertTrue(recovered)
        assertEquals("incoming", database.personalDataDao().allRatings().single().notes)
    }

    @Test
    fun unreadableJournalsAreQuarantinedInsteadOfLoopingAtEveryStartup() = runBlocking {
        context.filesDir.resolve("personal-restore-journal-v1.json").writeText("{")
        context.filesDir.resolve("personal-restore-journal-v1.json.backup").writeText("broken")
        val manager = manager(settingsRepository().also { it.loadInitialState() })

        assertTrue(runCatching { manager.recoverInterruptedImport() }.isFailure)
        assertFalse(manager.hasPendingRecovery())
        assertFalse(manager.recoverInterruptedImport())
    }

    @Test
    fun sharedExportIsAValidatedRoundTripFromAnAppPrivateCacheFile() = runBlocking {
        val settings = settingsRepository().also { it.loadInitialState() }
        database.personalDataDao().restore(restoreData("private note", 10L), replace = true)

        val shared = manager(settings).exportForSharing()
        try {
            assertTrue(shared.isFile)
            assertTrue(shared.canonicalPath.startsWith(context.cacheDir.canonicalPath))
            val decoded = decodePersonalDataBackup(shared.readText())
            assertEquals("private note", decoded.ratings.single().notes)
            assertEquals(PERSONAL_BACKUP_SOURCE, decoded.source)
        } finally {
            shared.delete()
        }
    }

    @Test
    fun scopedExportContainsOnlySelectedPackAndRejectsWrongImportScope() = runBlocking {
        val settings = settingsRepository().also { it.loadInitialState() }
        val dao = database.personalDataDao()
        dao.restore(restoreData("book note", 10L), replace = true)
        dao.restore(
            PersonalRestoreData(
                ratings = listOf(
                    MediaRatingEntity(
                        "mal:1", 0.0, 9.0, 9.0, "anime note", "completed", 20L,
                        "general", OfficialPackIds.Anime.value
                    )
                ),
                ratingScores = emptyList(),
                groupRatings = emptyList(),
                groupRatingScores = emptyList()
            ),
            replace = false
        )
        val manager = manager(settings)
        val shared = manager.exportForSharing(setOf(OfficialPackIds.Books))
        try {
            val decoded = decodePersonalDataBackup(shared.readText())
            assertEquals(setOf(OfficialPackIds.Books.value), decoded.scopePackIds)
            assertEquals(setOf(OfficialPackIds.Books.value), decoded.ratings.map { it.packId }.toSet())
            assertFalse(decoded.globalSettingsIncluded)
            assertTrue(
                runCatching {
                    manager.preview(Uri.fromFile(shared), setOf(OfficialPackIds.Anime))
                }.isFailure
            )
        } finally {
            shared.delete()
        }
    }

    private fun manager(
        settings: SettingsRepository,
        checkpoint: suspend (PersonalRestoreCheckpoint) -> Unit = {}
    ) = PersonalDataBackupManager(
        context,
        database,
        settings,
        CategoryPackManager(
            context,
            injectedDownloader = CategoryPackArchiveDownloader { _, _, _ ->
                CategoryPackDownloadResult.UNAVAILABLE
            },
            injectedDescriptors = emptyMap(),
            bundledCatalogsAvailable = false
        ),
        checkpoint
    )

    private fun settingsRepository() = SettingsRepository(
        context,
        object : AppIconAliasController {
            override fun activate(icon: AppIcon, previousIcon: AppIcon) = Unit
            override fun activeIconOrNull(): AppIcon = AppIcon.DARK
        },
        AppLocaleController { }
    )

    private fun preview(notes: String, updatedAt: Long, showImages: Boolean): PersonalBackupPreview {
        val backup = PersonalDataBackup(
            exportedAt = 1L,
            mediaSettings = mapOf(
                OfficialPackIds.Books.value to DefaultSettings.mediaSettings(MediaType.BOOK)
                    .copy(showImages = showImages)
            ),
            initializedPackIds = setOf(OfficialPackIds.Books.value),
            catalogSortOrders = mapOf(
                OfficialPackIds.Books.value to CatalogSortOrder.POPULARITY.name
            ),
            allViewSettings = AllViewSettings(),
            themeSelection = AppThemePalette.MIDNIGHT_NEON.persistedValue,
            themes = BackupThemeWorkspace(),
            ratings = listOf(
                BackupRating(
                    OfficialPackIds.Books.value,
                    ITEM_ID,
                    0.0,
                    8.0,
                    8.0,
                    notes,
                    "completed",
                    updatedAt,
                    "general",
                    listOf(BackupCategoryScore("writing", 8.0, true))
                )
            ),
            groupRatings = emptyList()
        )
        return PersonalBackupPreview(
            backup,
            ratingCount = 1,
            groupRatingCount = 0,
            packIds = setOf(OfficialPackIds.Books.value),
            missingPackIds = emptySet()
        )
    }

    private fun restoreData(notes: String, updatedAt: Long) = PersonalRestoreData(
        ratings = listOf(
            MediaRatingEntity(
                ITEM_ID,
                0.0,
                7.0,
                7.0,
                notes,
                "completed",
                updatedAt,
                "general",
                OfficialPackIds.Books.value
            )
        ),
        ratingScores = listOf(
            MediaRatingScoreEntity(
                ITEM_ID,
                "writing",
                7.0,
                true,
                OfficialPackIds.Books.value
            )
        ),
        groupRatings = emptyList(),
        groupRatingScores = emptyList()
    )

    private class SimulatedDeath : Error()

    companion object {
        private const val ITEM_ID = "book:openlibrary:OL1W"
    }
}
