package org.rankoffline.app

import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackManagerColdStartTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun completedRoomCatalogIsReadyAfterRuntimeStateIsLost() = runBlocking {
        val root = temporary.newFolder("packs")
        installCommittedPack(root)
        var syncCalls = 0
        val synchronizer = object : PackCatalogSynchronizer {
            override suspend fun sync(manifest: PackManifest, installedDirectory: File) {
                syncCalls++
            }

            override suspend fun isCurrentFast(
                manifest: PackManifest,
                installedDirectory: File
            ): Boolean = true
        }
        val manager = manager(root, synchronizer)

        manager.refresh()

        val state = manager.states.value.getValue(PackId(11))
        assertTrue(state.installed)
        assertEquals(PackRuntimeStatus.READY, state.status)
        assertEquals(0, syncCalls)
    }

    @Test fun staleRoomCatalogStillRequiresRecovery() = runBlocking {
        val root = temporary.newFolder("stale")
        installCommittedPack(root)
        val synchronizer = object : PackCatalogSynchronizer {
            override suspend fun sync(manifest: PackManifest, installedDirectory: File) = Unit
            override suspend fun isCurrentFast(
                manifest: PackManifest,
                installedDirectory: File
            ): Boolean = false
        }

        val manager = manager(root, synchronizer)
        manager.refresh()

        assertEquals(PackRuntimeStatus.FILES_INSTALLED,
            manager.states.value.getValue(PackId(11)).status)
    }

    private fun installCommittedPack(root: File) {
        val installer = PackInstaller(root)
        installer.install(PackInstallRequest(TestFixtures.manifestJson())) {
            ByteArrayInputStream(TestFixtures.catalogBytes)
        }
        installer.commitInstallation(PackId(11))
    }

    private fun manager(root: File, synchronizer: PackCatalogSynchronizer) = PackManager(
        packRoot = root,
        cacheRoot = temporary.newFolder(),
        sources = PackSourceRegistry(),
        downloader = PackArchiveDownloader { _, _, _ -> PackOperationResult.NETWORK_ERROR },
        synchronizer = synchronizer,
        catalogCleaner = PackCatalogCleaner {},
        currentAppVersion = "0.2.2"
    )
}
