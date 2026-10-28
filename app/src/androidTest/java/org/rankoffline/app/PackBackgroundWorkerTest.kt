package org.rankoffline.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackBackgroundWorkerTest {
    @Test
    fun workIdentityIsScopedPerPackAndOptionalCoversUseSeparateQueue() {
        val animeInstall = PackBackgroundWorker.uniqueName(MediaType.ANIME, PackWorkAction.INSTALL)
        val animeRecover = PackBackgroundWorker.uniqueName(MediaType.ANIME, PackWorkAction.RECOVER)
        val animeCovers = PackBackgroundWorker.uniqueName(MediaType.ANIME, PackWorkAction.INITIAL_COVERS)

        assertEquals(animeInstall, animeRecover)
        assertNotEquals(animeInstall, animeCovers)
        assertNotEquals(
            animeInstall,
            PackBackgroundWorker.uniqueName(MediaType.BOOK, PackWorkAction.INSTALL)
        )
    }

    @Test
    fun catalogRecoveryWorksOfflineButTransfersRequireNetwork() {
        val recover = PackBackgroundWorker.request(MediaType.ANIME, PackWorkAction.RECOVER)
        val install = PackBackgroundWorker.request(MediaType.ANIME, PackWorkAction.INSTALL)
        val covers = PackBackgroundWorker.request(MediaType.ANIME, PackWorkAction.INITIAL_COVERS)

        assertEquals(NetworkType.NOT_REQUIRED, recover.workSpec.constraints.requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, install.workSpec.constraints.requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, covers.workSpec.constraints.requiredNetworkType)
        assertTrue(install.tags.any { it == "pack-${OfficialPackIds.Anime.value}" })
        assertEquals(10_000L, install.workSpec.backoffDelayDuration)
    }

    @Test
    fun onlyRecoverablePackFailuresAreRetried() {
        assertEquals(
            PackWorkDisposition.RETRYABLE,
            CategoryPackDownloadResult.NETWORK_ERROR.workDisposition()
        )
        assertEquals(
            PackWorkDisposition.RETRYABLE,
            CategoryPackDownloadResult.RUNTIME_ERROR.workDisposition()
        )
        assertEquals(
            PackWorkDisposition.PERMANENT_FAILURE,
            CategoryPackDownloadResult.INVALID_PACK.workDisposition()
        )
        assertEquals(
            PackWorkDisposition.PERMANENT_FAILURE,
            CategoryPackDownloadResult.UNAVAILABLE.workDisposition()
        )
    }
}
