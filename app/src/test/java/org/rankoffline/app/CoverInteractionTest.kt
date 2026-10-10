package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverInteractionTest {
    @Test
    fun placeholderTapStartsDownloadOnly() {
        var downloads = 0
        var viewers = 0

        dispatchCoverTap(null, { downloads++ }, { viewers++ })

        assertEquals(1, downloads)
        assertEquals(0, viewers)
    }

    @Test
    fun downloadedCoverTapOpensViewerOnly() {
        var downloads = 0
        var viewers = 0

        dispatchCoverTap("/private/cover.jpg", { downloads++ }, { viewers++ })

        assertEquals(0, downloads)
        assertEquals(1, viewers)
    }

    @Test
    fun temporaryNetworkErrorsDoNotBecomeNotFound() {
        assertEquals(
            CoverResolutionState.UNKNOWN,
            CoverResolutionPolicy.afterDownload(
                CoverResolutionState.UNKNOWN,
                CoverDownloadResult.NetworkError
            )
        )
        assertEquals(
            CoverResolutionState.AVAILABLE,
            CoverResolutionPolicy.afterDownload(
                CoverResolutionState.AVAILABLE,
                CoverDownloadResult.TemporaryError
            )
        )
        assertEquals(
            CoverResolutionState.NOT_FOUND,
            CoverResolutionPolicy.afterDownload(
                CoverResolutionState.UNKNOWN,
                CoverDownloadResult.NotFound
            )
        )
    }

    @Test
    fun top3ScoreTapRoutesToRatingAction() {
        var openedRatings = 0

        dispatchTop3ScoreTap { openedRatings++ }

        assertEquals(1, openedRatings)
    }

    @Test
    fun completedAndFailedSummariesAreClearedAfterLeavingSettings() {
        assertEquals(
            CoverPreloadStatus.IDLE,
            cleanupAfterExit(
                CoverPreloadProgress(status = CoverPreloadStatus.COMPLETED, target = 100, processed = 100)
            ).status
        )
        assertEquals(
            CoverPreloadStatus.IDLE,
            cleanupAfterExit(
                CoverPreloadProgress(status = CoverPreloadStatus.FAILED, target = 100, errors = 1)
            ).status
        )
        assertFalse(
            CoverPreloadProgress(status = CoverPreloadStatus.DOWNLOADING_COVERS, target = 100, processed = 50)
                .shouldClearAfterLeavingSettings()
        )
    }

    @Test
    fun activeUpdateIsKeptOnExitThenClearedWhenItFinishesWhileAway() {
        var generalSettingsVisible = true
        var progress = CoverPreloadProgress(
            status = CoverPreloadStatus.DOWNLOADING_COVERS,
            target = 100,
            processed = 50
        )

        generalSettingsVisible = false
        if (!generalSettingsVisible) progress = cleanupAfterExit(progress)
        assertEquals(CoverPreloadStatus.DOWNLOADING_COVERS, progress.status)

        progress = progress.copy(status = CoverPreloadStatus.FAILED, errors = 1)
        if (!generalSettingsVisible) progress = cleanupAfterExit(progress)
        assertEquals(CoverPreloadStatus.IDLE, progress.status)

        generalSettingsVisible = true
        assertEquals(CoverPreloadStatus.IDLE, progress.status)
        assertTrue(generalSettingsVisible)
    }

    @Test
    fun enteringGeneralSettingsDoesNotClearTerminalState() {
        val progress = CoverPreloadProgress(
            status = CoverPreloadStatus.COMPLETED,
            target = 100,
            processed = 100
        )
        val generalSettingsVisible = true

        if (!generalSettingsVisible && progress.shouldClearAfterLeavingSettings()) {
            throw AssertionError("Cleanup must not run on enter")
        }

        assertEquals(CoverPreloadStatus.COMPLETED, progress.status)
    }

    @Test
    fun topUpdateStartsInPopularityPhaseWithoutCoverErrors() {
        val progress = TopUpdateProgress.start(500)

        assertEquals(CoverPreloadStatus.FETCHING_POPULARITY, progress.status)
        assertEquals(0, progress.processed)
        assertEquals(0, progress.errors)
        assertFalse(progress.popularityUpdated)
    }

    @Test
    fun topUpdateMovesFromPopularityToCoversAndCompletes() {
        val fetching = TopUpdateProgress.popularityFetched(TopUpdateProgress.start(500), 250)
        assertEquals(250, fetching.popularityProcessed)
        assertEquals(0.5f, fetching.popularityFraction, 0f)

        val applying = TopUpdateProgress.applyingPopularity(fetching)
        assertEquals(CoverPreloadStatus.APPLYING_POPULARITY, applying.status)

        val covers = TopUpdateProgress.downloadingCovers(applying, processed = 80, existing = 75, errors = 0)
        assertEquals(CoverPreloadStatus.DOWNLOADING_COVERS, covers.status)
        assertTrue(covers.popularityUpdated)
        assertEquals(80, covers.processed)

        val completed = TopUpdateProgress.completed(covers)
        assertEquals(CoverPreloadStatus.COMPLETED, completed.status)
        assertEquals(500, completed.processed)
    }

    @Test
    fun popularityFailureDoesNotPretendCoversCompleted() {
        val failed = TopUpdateProgress.start(100).copy(status = CoverPreloadStatus.FAILED)

        assertFalse(failed.popularityUpdated)
        assertEquals(0, failed.processed)
        assertEquals(0, failed.errors)
        assertTrue(failed.shouldClearAfterLeavingSettings())
    }

    private fun cleanupAfterExit(progress: CoverPreloadProgress): CoverPreloadProgress =
        if (progress.shouldClearAfterLeavingSettings()) CoverPreloadProgress() else progress
}
