package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {
    @Test
    fun emptyPreferencesShowBothFirstRunSteps() {
        val state = resolveOnboardingState(null)

        assertFalse(state.hasCompletedWelcome)
        assertFalse(state.hasCompletedOnboarding)
    }

    @Test
    fun pre020InstallRunsGenerationTwoOnboardingOnce() {
        val state = resolveOnboardingState(null)

        assertFalse(state.hasCompletedWelcome)
        assertFalse(state.hasCompletedOnboarding)
    }

    @Test
    fun completedGenerationTwoDoesNotRepeat() {
        val state = resolveOnboardingState(CURRENT_ONBOARDING_VERSION)

        assertTrue(state.hasCompletedWelcome)
        assertTrue(state.hasCompletedOnboarding)
    }

    @Test
    fun laterPatchInSameGenerationDoesNotRepeat() {
        val state = resolveOnboardingState(CURRENT_ONBOARDING_VERSION + 1)

        assertTrue(state.hasCompletedWelcome)
        assertTrue(state.hasCompletedOnboarding)
    }

    @Test
    fun ratingChoiceUsesEveryExistingMediaSettingWithoutReplacingCustomConfiguration() {
        val custom = DefaultSettings.allMediaSettings().mapValues { (mediaType, settings) ->
            settings.copy(mergeEnabled = mediaType == MediaType.MANGA)
        }

        val updated = applyOnboardingRatingStyle(custom, elaborateRating = true)

        assertTrue(updated.values.all(MediaSettings::elaborateRatingEnabled))
        assertEquals(custom.getValue(MediaType.MANGA).ratingConfig, updated.getValue(MediaType.MANGA).ratingConfig)
        assertEquals(custom.getValue(MediaType.MANGA).ratingProfiles, updated.getValue(MediaType.MANGA).ratingProfiles)
        assertTrue(updated.getValue(MediaType.MANGA).mergeEnabled)
    }

    @Test
    fun downloadedCatalogAndCoversAreTruthfullyReclaimable() {
        val state = CategoryPackState(
            MediaType.MOVIE,
            CategoryPackOrigin.DOWNLOADED,
            catalogBytes = 10_000,
            downloadedCoverBytes = 600,
            reclaimableBytes = 10_600,
            downloadBytes = 8_000,
            downloadConfigured = true
        )

        assertEquals(10_600, state.installedBytes)
        assertEquals(10_600, state.finalStorageBytes)
        assertEquals(10_600, state.reclaimableBytes)
        assertTrue(state.canDelete)
        assertFalse(state.canDownload)
    }

    @Test
    fun bundledCatalogBytesAreAvailableButNeverReportedAsReclaimable() {
        val state = CategoryPackState(
            MediaType.ANIME,
            CategoryPackOrigin.BUNDLED,
            catalogBytes = 5_000,
            downloadedCoverBytes = 250,
            reclaimableBytes = 250,
            downloadBytes = 4_000,
            downloadConfigured = false
        )

        assertTrue(state.available)
        assertFalse(state.canDelete)
        assertEquals(5_250, state.installedBytes)
        assertEquals(250, state.reclaimableBytes)
    }

    @Test
    fun unavailablePackCannotPretendToDownloadWithoutConfiguredHosting() {
        val state = CategoryPackState(
            MediaType.BOOK,
            CategoryPackOrigin.UNAVAILABLE,
            catalogBytes = 0,
            downloadedCoverBytes = 0,
            reclaimableBytes = 0,
            downloadBytes = 6_000,
            downloadConfigured = false
        )

        assertFalse(state.available)
        assertFalse(state.canDownload)
        assertEquals(6_000L, state.downloadBytes)
    }

    @Test
    fun unavailablePackExposesSeparateDownloadAndInstalledSizes() {
        val state = CategoryPackState(
            MediaType.SERIES,
            CategoryPackOrigin.UNAVAILABLE,
            catalogBytes = 0,
            downloadedCoverBytes = 0,
            reclaimableBytes = 0,
            downloadBytes = 4_800,
            declaredInstalledBytes = 19_400,
            downloadConfigured = true
        )

        assertEquals(4_800L, state.downloadBytes)
        assertEquals(19_400L, state.declaredInstalledBytes)
        assertEquals(19_400L, state.finalStorageBytes)
        assertTrue(state.canDownload)
    }

    @Test
    fun catalogReadinessIsIndependentFromOptionalCoverAcquisition() {
        val importing = CategoryPackState(
            MediaType.MANGA,
            CategoryPackOrigin.DOWNLOADED,
            catalogBytes = 12_000,
            downloadedCoverBytes = 0,
            reclaimableBytes = 12_000,
            runtimeStatus = CategoryPackRuntimeStatus.INITIALIZING,
            installProgress = CategoryPackInstallProgress(
                phase = CategoryPackInstallPhase.IMPORTING_GROUPS,
                downloadedBytes = 3_000,
                totalDownloadBytes = 3_000
            ),
            downloadConfigured = true
        )

        assertFalse(importing.available)
        assertFalse(importing.canDelete)
        assertFalse(importing.canDownload)

        val downloadingCovers = importing.copy(
            runtimeStatus = CategoryPackRuntimeStatus.READY,
            installProgress = importing.installProgress?.copy(
                phase = CategoryPackInstallPhase.DOWNLOADING_COVERS,
                coverTarget = 100,
                coverProcessed = 25
            )
        )
        assertTrue(downloadingCovers.available)
        assertFalse(downloadingCovers.canDelete)
        assertTrue(downloadingCovers.copy(installProgress = null).canDelete)
    }

    @Test
    fun installedFilesAreNotReadyUntilRuntimeInitializationCompletes() {
        val filesOnly = CategoryPackState(
            mediaType = MediaType.SERIES,
            origin = CategoryPackOrigin.DOWNLOADED,
            catalogBytes = 4_000,
            downloadedCoverBytes = 0,
            reclaimableBytes = 4_000,
            runtimeStatus = CategoryPackRuntimeStatus.FILES_INSTALLED
        )

        assertFalse(filesOnly.available)
        assertTrue(filesOnly.copy(runtimeStatus = CategoryPackRuntimeStatus.READY).available)
        assertFalse(filesOnly.copy(runtimeStatus = CategoryPackRuntimeStatus.ERROR).available)
    }

    @Test
    fun onboardingHasNoSelectionUntilTheActivePackIsReady() {
        val downloading = CategoryPackState(
            mediaType = MediaType.ANIME,
            origin = CategoryPackOrigin.UNAVAILABLE,
            catalogBytes = 0,
            downloadedCoverBytes = 0,
            reclaimableBytes = 0,
            installProgress = CategoryPackInstallProgress(
                phase = CategoryPackInstallPhase.DOWNLOADING,
                downloadedBytes = 50,
                totalDownloadBytes = 100
            ),
            runtimeStatus = CategoryPackRuntimeStatus.INITIALIZING
        )

        assertEquals(
            null,
            onboardingSelectedMediaType(MediaType.ANIME, mapOf(MediaType.ANIME to downloading))
        )

        val ready = downloading.copy(
            origin = CategoryPackOrigin.DOWNLOADED,
            runtimeStatus = CategoryPackRuntimeStatus.READY,
            installProgress = downloading.installProgress?.copy(
                phase = CategoryPackInstallPhase.DOWNLOADING_COVERS
            )
        )
        assertEquals(
            MediaType.ANIME,
            onboardingSelectedMediaType(MediaType.ANIME, mapOf(MediaType.ANIME to ready))
        )
    }

    @Test
    fun onboardingReturnsToNoSelectionAfterTheLastPackIsRemoved() {
        val removed = CategoryPackState(
            mediaType = MediaType.SERIES,
            origin = CategoryPackOrigin.UNAVAILABLE,
            catalogBytes = 0,
            downloadedCoverBytes = 0,
            reclaimableBytes = 0,
            runtimeStatus = CategoryPackRuntimeStatus.FILES_MISSING
        )

        assertEquals(
            null,
            onboardingSelectedMediaType(MediaType.SERIES, mapOf(MediaType.SERIES to removed))
        )
        assertEquals(
            null,
            resolveAvailableMediaType(MediaType.SERIES, mapOf(MediaType.SERIES to removed))
        )
    }

    @Test
    fun firstReadyPackBecomesTheActiveSelection() {
        val unavailableAnime = CategoryPackState(
            mediaType = MediaType.ANIME,
            origin = CategoryPackOrigin.UNAVAILABLE,
            catalogBytes = 0,
            downloadedCoverBytes = 0,
            reclaimableBytes = 0
        )
        val readyManga = CategoryPackState(
            mediaType = MediaType.MANGA,
            origin = CategoryPackOrigin.DOWNLOADED,
            catalogBytes = 1,
            downloadedCoverBytes = 0,
            reclaimableBytes = 1,
            runtimeStatus = CategoryPackRuntimeStatus.READY
        )

        assertEquals(
            MediaType.MANGA,
            resolveAvailableMediaType(
                MediaType.ANIME,
                mapOf(MediaType.ANIME to unavailableAnime, MediaType.MANGA to readyManga)
            )
        )
    }

    @Test
    fun networkProgressKeepsActualArchiveBytesAndTrueFraction() {
        val progress = CategoryPackInstallProgress(
            phase = CategoryPackInstallPhase.DOWNLOADING,
            downloadedBytes = 2_500,
            totalDownloadBytes = 5_000,
            totalInstalledBytes = 20_000
        )

        assertEquals(2_500L, progress.downloadedBytes)
        assertEquals(5_000L, progress.totalDownloadBytes)
        assertEquals(0.5f, progress.fraction, 0.0f)
    }

    @Test
    fun aggregateDownloadProgressUsesCombinedByteTotals() {
        val progress = aggregateCategoryDownloadProgress(
            transfers = listOf(5L to 20L, 30L to 60L),
            currentMediaType = MediaType.MOVIE,
            currentPosition = 2,
            transferCount = 3
        )

        assertEquals(35L, progress.downloadedBytes)
        assertEquals(80L, progress.totalBytes)
        assertEquals(0.4375f, progress.fraction, 0.0001f)
        assertEquals(MediaType.MOVIE, progress.currentMediaType)
        assertEquals(2, progress.currentPosition)
        assertEquals(3, progress.transferCount)
    }

    @Test
    fun ratingOverrideUpdatesOnlyMatchingVisibleRow() {
        val original = listOf(
            Anime(id = "movie:tmdb:1", title = "One", mediaType = MediaType.MOVIE),
            Anime(id = "movie:tmdb:2", title = "Two", mediaType = MediaType.MOVIE, finalScore = 7.0)
        )

        val updated = applyRatingOverrides(
            original,
            mapOf("movie:tmdb:2" to RatingDisplayOverride(8.5, 8.0, 0.5))
        )

        assertTrue(updated[0] === original[0])
        assertEquals(8.5, updated[1].finalScore ?: -1.0, 0.0)
        assertEquals(8.0, updated[1].baseScore ?: -1.0, 0.0)
        assertEquals(0.5, updated[1].additiveScore ?: -1.0, 0.0)
    }
}
