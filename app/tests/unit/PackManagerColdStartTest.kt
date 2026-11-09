package org.rankoffline.app

import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun deletionRemainsVisibleUntilFilesAreGoneThenRestoresDownloadAction() = runBlocking {
        val id = PackId(11)
        val root = temporary.newFolder("deleting")
        installCommittedPack(root)
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sources = PackSourceRegistry().apply { replace(listOf(PackDownloadDescriptor(
            TestFixtures.manifest(), URI("https://example.com/pack.pack"), 1,
            "0".repeat(64), "fixture"
        ))) }
        val manager = PackManager(root, temporary.newFolder(), sources,
            PackArchiveDownloader { _, _, _ -> PackOperationResult.NETWORK_ERROR },
            object : PackCatalogSynchronizer {
                override suspend fun sync(manifest: PackManifest, installedDirectory: File) = Unit
                override suspend fun isCurrentFast(manifest: PackManifest,
                    installedDirectory: File) = true
            }, PackCatalogCleaner { cleaning.complete(Unit); release.await() }, "0.2.2")
        manager.refresh()
        manager.markDeleting(id)
        val deletion = async { manager.delete(id) }
        try {
            withTimeout(5_000) { cleaning.await() }
            manager.refresh() // Files still exist; refresh must not restore READY.
            val pending = manager.states.value.getValue(id)
            assertEquals(PackRuntimeStatus.DELETING, pending.status)
            assertFalse(pending.canDownload)
            assertFalse(pending.canDelete)
        } finally { release.complete(Unit) }
        assertTrue(withTimeout(5_000) { deletion.await() })
        val removed = manager.states.value.getValue(id)
        assertEquals(PackRuntimeStatus.NOT_INSTALLED, removed.status)
        assertFalse(removed.installed)
        assertTrue(removed.canDownload)
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
