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
    fun groupCoverUsesItsRepresentativeAnime() {
        val group = Anime(
            id = "group:mal:1:SERIES",
            title = "Series group",
            groupId = "group:mal:1:SERIES",
            rootAnimeId = "mal:10"
        )

        val cover = group.coverTarget()

        assertEquals("mal:10", cover.id)
        assertEquals(null, cover.groupId)
    }

    @Test
    fun tmdbGroupsKeepTheirOwnCoverTarget() {
        val group = Anime(
            id = "movie:tmdb:collection:10",
            title = "Collection",
            mediaType = MediaType.MOVIE,
            groupId = "movie:tmdb:collection:10",
            groupType = "COLLECTION",
            rootAnimeId = "movie:tmdb:11"
        )

        assertEquals(group.id, group.coverTarget().id)
        assertTrue(group.supportsRemoteGroupCover())
        assertEquals(CoverStorageKind.GENERIC_GROUP, group.coverStorageIdentity().kind)
        assertFalse(group.copy(mediaType = MediaType.MANGA).supportsRemoteGroupCover())
        assertEquals(
            CoverStorageKind.GENERIC_ITEM,
            group.copy(mediaType = MediaType.MANGA).coverTarget().coverStorageIdentity().kind
        )
    }

    @Test
    fun sharedGroupCoverCanBeEnabledAndDisabledWithoutChangingStoredCover() {
        val member = Anime(
            id = "movie:tmdb:2",
            title = "Second",
            mediaType = MediaType.MOVIE,
            localCoverPath = "/covers/second.webp",
            sharedCoverMediaId = "movie:tmdb:1"
        )

        assertEquals(member.id, member.coverTarget(oneCoverPerGroup = false).id)
        assertEquals("/covers/second.webp", member.coverTarget(false).localCoverPath)
        val shared = member.coverTarget(oneCoverPerGroup = true)
        assertEquals("movie:tmdb:1", shared.id)
        assertEquals(null, shared.localCoverPath)
        assertEquals("/covers/second.webp", member.localCoverPath)
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

    private fun cleanupAfterExit(progress: CoverPreloadProgress): CoverPreloadProgress =
        if (progress.shouldClearAfterLeavingSettings()) CoverPreloadProgress() else progress
}
