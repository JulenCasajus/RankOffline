package org.rankoffline.app

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackInstallerAtomicityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun verifiedInstallIsPendingUntilRoomCommit() {
        val installer = PackInstaller(temporary.newFolder("packs"))
        val request = PackInstallRequest(TestFixtures.manifestJson())
        val directory = installer.install(request) { ByteArrayInputStream(TestFixtures.catalogBytes) }

        assertTrue(installer.hasPendingInstallation(PackId(11)))
        assertArrayEquals(TestFixtures.catalogBytes, File(directory, "catalog/items.jsonl").readBytes())
        installer.commitInstallation(PackId(11))
        assertFalse(installer.hasPendingInstallation(PackId(11)))
        assertTrue(installer.matchesInstalledManifest(TestFixtures.manifest(), verifyPayloadHashes = true))
    }

    @Test fun hashMismatchNeverPromotesPartialPack() {
        val root = temporary.newFolder("invalid")
        val installer = PackInstaller(root)
        assertThrows(Exception::class.java) {
            installer.install(PackInstallRequest(TestFixtures.manifestJson())) {
                ByteArrayInputStream("wrong".toByteArray())
            }
        }
        assertNull(installer.installedDirectory(PackId(11)))
        assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
    }

    @Test fun completeInterruptedInstallRequestsIdempotentImportReplay() {
        val installer = PackInstaller(temporary.newFolder("recover"))
        installer.install(PackInstallRequest(TestFixtures.manifestJson())) {
            ByteArrayInputStream(TestFixtures.catalogBytes)
        }
        assertTrue(installer.recover(PackId(11)) == PackRecoveryState.AWAITING_COMMIT)
    }

    @Test fun abortRemovesOnlyTheUncommittedInstallation() {
        val installer = PackInstaller(temporary.newFolder("abort"))
        installer.install(PackInstallRequest(TestFixtures.manifestJson())) {
            ByteArrayInputStream(TestFixtures.catalogBytes)
        }
        installer.abortInstallation(PackId(11))
        assertNull(installer.installedDirectory(PackId(11)))
        assertFalse(installer.hasPendingInstallation(PackId(11)))
    }

    @Test fun deletionIsScopedToOneNumericPack() {
        val root = temporary.newFolder("delete")
        val installer = PackInstaller(root)
        listOf(11L, 12L).forEach { id ->
            installer.install(PackInstallRequest(TestFixtures.manifestJson(id))) {
                ByteArrayInputStream(TestFixtures.catalogBytes)
            }
            installer.commitInstallation(PackId(id))
        }
        assertTrue(installer.deleteInstalledPack(PackId(11)))
        assertNull(installer.installedDirectory(PackId(11)))
        assertTrue(installer.installedDirectory(PackId(12))?.isDirectory == true)
    }
}
