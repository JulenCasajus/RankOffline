package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAndWorkContractTest {
    @Test fun manifestDefaultsResolveWithoutOfficialPackKnowledge() {
        val manifest = TestFixtures.manifest(11, merge = true)
        val settings = manifest.resolveSettings(PersonalPackPreferences())
        assertTrue(settings.mergeEnabled)
        assertTrue(settings.showImages)
        assertFalse(settings.titlesOnPodium)
        assertFalse(settings.personalTasteEnabled)
        assertFalse(AllViewSettings().titlesOnPodium)
        assertEquals(listOf("writing"), settings.ratingConfig.categories.map { it.id })
    }

    @Test fun personalPreferencesOverrideOnlyTheirOwnPackSettings() {
        val manifest = TestFixtures.manifest(11, merge = true)
        val settings = manifest.resolveSettings(PersonalPackPreferences(
            showImages = false, mergeEnabled = false, titlesOnPodium = true))
        assertFalse(settings.showImages)
        assertFalse(settings.mergeEnabled)
        assertTrue(settings.titlesOnPodium)
        assertTrue(manifest.resolveSettings(
            PersonalPackPreferences(personalTasteEnabled = true)).personalTasteEnabled)
        val saved = RatingDraft(additiveScore = 0.75, finalScore = 8.75, qualityScore = 8.0)
        assertEquals(0.0, saved.forPersonalTaste(false).additiveScore, 0.0)
        assertEquals(0.75, saved.additiveScore, 0.0)
        assertEquals(8.75, saved.finalScore!!, 0.0)
        assertEquals(saved, saved.forPersonalTaste(true))
    }

    @Test fun mergeCannotBeEnabledWhenManifestDisablesIt() {
        val settings = TestFixtures.manifest().resolveSettings(PersonalPackPreferences(mergeEnabled = true))
        assertFalse(settings.mergeEnabled)
    }

    @Test fun viewingScopeZeroIsVirtualAndNeverCreatesPhysicalPackId() {
        assertEquals(ViewingScope.All, ViewingScope.fromStored(0L))
        assertEquals(0L, ViewingScope.All.storedId())
        assertEquals(ViewingScope.Pack(PackId(5)), ViewingScope.fromStored(5L))
        assertEquals("pack-5", PackId(5).directoryName())
    }

    @Test fun workDispositionRetriesOnlyRecoverableFailures() {
        assertEquals(PackWorkDisposition.SUCCESS, PackOperationResult.INSTALLED.toWorkDisposition())
        listOf(PackOperationResult.NETWORK_ERROR, PackOperationResult.IMPORT_ERROR,
            PackOperationResult.STORAGE_ERROR).forEach {
            assertEquals(PackWorkDisposition.RETRYABLE, it.toWorkDisposition())
        }
        listOf(PackOperationResult.NOT_FOUND, PackOperationResult.INVALID_PACK).forEach {
            assertEquals(PackWorkDisposition.PERMANENT_FAILURE, it.toWorkDisposition())
        }
    }

    @Test fun progressFractionsAreBoundedAndUnknownTotalsStayZero() {
        assertEquals(0f, PackInstallProgress(PackInstallPhase.DOWNLOADING).downloadFraction)
        assertEquals(1f, PackInstallProgress(PackInstallPhase.DOWNLOADING, 20, 10).downloadFraction)
        assertEquals(0.5f, PackInstallProgress(PackInstallPhase.DOWNLOADING, 5, 10).downloadFraction)
    }

    @Test fun installWorkCarriesItsPersistentFifoSequence() {
        val request = PackWorkScheduler.request(PackId(11), PackWorkAction.INSTALL, 7L)
        assertEquals(7L, request.workSpec.input.getLong(PackWorkScheduler.KEY_QUEUE_SEQUENCE, 0L))
        assertTrue(request.tags.contains("rankoffline-pack-sequence:7"))
    }

    @Test fun initialCoverTargetRemainsVisibleWhileSelectingCandidates() {
        val id = PackId(11)
        val tracker = PackProgressTracker()
        tracker.set(id, PackInstallProgress(PackInstallPhase.DOWNLOADING_COVERS,
            coverTarget = 100))
        tracker.covers(id, CoverDownloadProgress(id, CoverDownloadStatus.PREPARING_COVER_LIST))
        assertEquals(0, tracker.progress.value[id]?.coverProcessed)
        assertEquals(100, tracker.progress.value[id]?.coverTarget)
    }

    @Test fun coverBytesAreNotDoubleCountedInStorageTotal() {
        val storage = PackStorageSnapshot(installedBytes = 100, coverBytes = 30, residualBytes = 20)
        assertEquals(120L, storage.totalBytes)
    }

    @Test fun packActionsRequireReadyOrSourceStateAsAppropriate() {
        val manifest = TestFixtures.manifest()
        val ready = PackState(PackId(11), manifest, true, true, PackRuntimeStatus.READY,
            10, PackStorageSnapshot(10, 0))
        val remote = ready.copy(installed = false, status = PackRuntimeStatus.NOT_INSTALLED)
        assertTrue(ready.available)
        assertFalse(ready.canDownload)
        assertTrue(remote.canDownload)
        assertFalse(remote.canDelete)
    }

    @Test fun launcherIconsAndLanguagesHaveStableUniquePersistedValues() {
        assertEquals(AppIcon.entries.size, AppIcon.entries.map { it.persistedValue }.distinct().size)
        AppIcon.entries.forEach { assertEquals(it, AppIcon.fromPersistedValue(it.persistedValue)) }
        assertEquals(AppIcon.DARK, AppIcon.fromPersistedValue("unknown"))
        assertEquals(9, SupportedLanguage.entries.size)
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.fromLanguageTag("unknown"))
    }

    @Test fun missingActivePackHasSafeSettingsDefaults() {
        val state = AppSettingsState()
        assertNull(state.activePackId)
        assertFalse(state.showImages)
        assertFalse(state.mergeEnabled)
        assertTrue(state.stickyScore)
    }
}
