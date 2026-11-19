package org.rankoffline.app

import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackInstallerUpdateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = PackId(11)
    private fun manifest(version: String) = JSONObject(TestFixtures.manifestJson())
        .put("version", version).put("slug", version.substringBeforeLast("-v")).toString()

    @Test fun newerRevisionRequiresTheSameLineageAndPackIdentity() {
        val old = PackManifestParser.parse(manifest("fixture-v5"))
        fun remote(version: String, id: Long = 11) = PackDownloadDescriptor(
            PackManifestParser.parse(JSONObject(TestFixtures.manifestJson(id))
                .put("version", version).put("slug", version.substringBeforeLast("-v")).toString()),
            URI("https://example.com/pack-$id.pack"), 100, "a".repeat(64), "fixture-$id")
        assertTrue(newerPackRevision(old, remote("fixture-v6")))
        assertFalse(newerPackRevision(old, remote("fixture-v5")))
        assertFalse(newerPackRevision(old, remote("fixture-v4")))
        assertFalse(newerPackRevision(old, remote("different-v9")))
        assertFalse(newerPackRevision(old, remote("fixture-v6", 12)))
    }

    @Test fun interruptedPromotionRestoresTheOriginalPackAndItsCovers() {
        val installer = PackInstaller(temporary.newFolder("interrupted"))
        installer.install(PackInstallRequest(manifest("fixture-v1"))) {
            ByteArrayInputStream(TestFixtures.catalogBytes)
        }
        installer.commitInstallation(id)
        val cover = File(installer.packDirectory(id), "covers/items/${"a".repeat(64)}.cover")
        assertTrue(cover.parentFile!!.mkdirs())
        cover.writeText("existing local cover")

        installer.stageUpdate(PackInstallRequest(manifest("fixture-v2"))) {
            ByteArrayInputStream(TestFixtures.catalogBytes)
        }
        assertTrue(installer.hasPendingUpdate(id))
        assertEquals("existing local cover", cover.readText())
        assertEquals(PackRecoveryState.CLEAN, installer.recover(id))
        assertFalse(installer.hasPendingUpdate(id))
        assertEquals("fixture-v1", readInstalledManifest(installer.packDirectory(id))?.version)
        assertEquals("existing local cover", cover.readText())
    }

    @Test fun committedReplacementSurvivesRecovery() {
        val installer = PackInstaller(temporary.newFolder("committed"))
        installer.install(PackInstallRequest(manifest("fixture-v1"))) {
            ByteArrayInputStream(TestFixtures.catalogBytes)
        }
        installer.commitInstallation(id)
        installer.stageUpdate(PackInstallRequest(manifest("fixture-v2"))) {
            ByteArrayInputStream(TestFixtures.catalogBytes)
        }
        installer.commitUpdate(id)
        assertEquals(PackRecoveryState.CLEAN, installer.recover(id))
        assertEquals("fixture-v2", readInstalledManifest(installer.packDirectory(id))?.version)
        assertFalse(installer.hasPendingUpdate(id))
    }

    @Test fun failureBeforePromotionDoesNotChangeOldFiles() {
        val installer = PackInstaller(temporary.newFolder("invalid"))
        installer.install(PackInstallRequest(manifest("fixture-v1"))) {
            ByteArrayInputStream(TestFixtures.catalogBytes)
        }
        installer.commitInstallation(id)
        val result = runCatching {
            installer.stageUpdate(PackInstallRequest(manifest("fixture-v2"))) {
                ByteArrayInputStream("wrong payload".toByteArray())
            }
        }
        assertTrue(result.isFailure)
        assertFalse(installer.hasPendingUpdate(id))
        assertEquals("fixture-v1", readInstalledManifest(installer.packDirectory(id))?.version)
    }

    @Test fun canonicalSlugCannotBeReassignedDuringUpdate() {
        val installer = PackInstaller(temporary.newFolder("slug-change"))
        installer.install(PackInstallRequest(manifest("fixture-v1"))) {
            ByteArrayInputStream(TestFixtures.catalogBytes)
        }
        installer.commitInstallation(id)
        val rejected = runCatching {
            installer.stageUpdate(PackInstallRequest(manifest("other-v2"))) {
                ByteArrayInputStream(TestFixtures.catalogBytes)
            }
        }
        assertTrue(rejected.isFailure)
        assertEquals("fixture-v1", readInstalledManifest(installer.packDirectory(id))?.version)
    }
}
