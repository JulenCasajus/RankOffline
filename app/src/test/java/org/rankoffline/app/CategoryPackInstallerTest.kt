package org.rankoffline.app

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CategoryPackInstallerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun validPackIsInstalledAtomically() {
        val bytes = "real catalog bytes".toByteArray()
        val manifest = manifest(bytes)
        val installer = CategoryPackInstaller(temporaryFolder.newFolder("packs"))

        val installed = installer.install(manifest) { ByteArrayInputStream(bytes) }

        assertNotNull(installer.installedDirectory(MediaType.BOOK))
        assertArrayEquals(bytes, File(installed, "catalog/book_catalog.jsonl.gzip").readBytes())
    }

    @Test
    fun invalidHashNeverActivatesPartialPack() {
        val bytes = "real catalog bytes".toByteArray()
        val manifest = manifest(bytes).copy(
            files = listOf(manifest(bytes).files.single().copy(sha256 = "0".repeat(64)))
        )
        val installer = CategoryPackInstaller(temporaryFolder.newFolder("packs"))

        runCatching { installer.install(manifest) { ByteArrayInputStream(bytes) } }

        assertFalse(File(temporaryFolder.root, "packs/books").exists())
        assertFalse(temporaryFolder.root.walkTopDown().any { it.name.endsWith(".part") })
    }

    @Test
    fun invalidReplacementPreservesPreviouslyInstalledPack() {
        val original = "original catalog".toByteArray()
        val replacement = "broken replacement".toByteArray()
        val installer = CategoryPackInstaller(temporaryFolder.newFolder("replace-packs"))
        installer.install(manifest(original)) { ByteArrayInputStream(original) }

        val invalid = manifest(replacement).copy(
            files = listOf(manifest(replacement).files.single().copy(sha256 = "f".repeat(64)))
        )
        assertTrue(runCatching { installer.install(invalid) { ByteArrayInputStream(replacement) } }.isFailure)

        val installed = installer.installedDirectory(MediaType.BOOK)
        assertNotNull(installed)
        assertArrayEquals(original, File(installed, "catalog/book_catalog.jsonl.gzip").readBytes())
    }

    @Test
    fun interruptedPromotionRestoresPreviousCompletePackAndDropsPartialData() {
        val root = temporaryFolder.newFolder("recover-packs")
        val installer = CategoryPackInstaller(root)
        val previous = File(root, ".books.previous").apply { mkdirs() }
        File(previous, CategoryPackInstaller.COMPLETE_MARKER).writeText("snapshot")
        File(previous, CategoryPackInstaller.PACK_MANIFEST).writeText(manifest("preserved".toByteArray()).encodedManifest)
        File(previous, "catalog").mkdirs()
        File(previous, "catalog/book_catalog.jsonl.gzip").writeText("preserved")
        File(root, ".books-abandoned.part").apply { mkdirs() }

        installer.recover(MediaType.BOOK)

        assertNotNull(installer.installedDirectory(MediaType.BOOK))
        assertEquals(
            "preserved",
            File(requireNotNull(installer.installedDirectory(MediaType.BOOK)), "catalog/book_catalog.jsonl.gzip").readText()
        )
        assertFalse(File(root, ".books-abandoned.part").exists())
    }

    @Test
    fun deletionRemovesInstalledRetainedAndStagingArtifactsForOnlyThatPack() {
        val root = temporaryFolder.newFolder("delete-packs")
        val installer = CategoryPackInstaller(root)
        val owned = listOf(
            File(root, "books"),
            File(root, ".books.previous"),
            File(root, ".books-abandoned.part")
        )
        owned.forEachIndexed { index, directory ->
            File(directory, "data/file-$index.bin").apply {
                parentFile?.mkdirs()
                writeText("owned")
            }
        }
        val unrelated = File(root, "animes/keep.bin").apply {
            parentFile?.mkdirs()
            writeText("keep")
        }

        assertTrue(installer.residualBytes(OfficialPackIds.Books) > 0L)
        assertTrue(installer.deleteInstalledPack(OfficialPackIds.Books))

        assertTrue(owned.none(File::exists))
        assertTrue(unrelated.isFile)
        assertEquals(0L, installer.residualBytes(OfficialPackIds.Books))
    }

    @Test
    fun interruptedDeletionCannotRestoreRetainedInstallation() {
        val root = temporaryFolder.newFolder("interrupted-delete")
        val installer = CategoryPackInstaller(root)
        File(root, ".books.deleting").writeText("deleting")
        listOf(File(root, "books"), File(root, ".books.previous")).forEach { directory ->
            File(directory, CategoryPackInstaller.COMPLETE_MARKER).apply {
                parentFile?.mkdirs()
                writeText("snapshot")
            }
            File(directory, CategoryPackInstaller.PACK_MANIFEST).writeText("manifest")
        }

        assertTrue(installer.recover(OfficialPackIds.Books))

        assertFalse(File(root, "books").exists())
        assertFalse(File(root, ".books.previous").exists())
        assertFalse(File(root, ".books.deleting").exists())
        assertFalse(installer.hasPendingDeletion(OfficialPackIds.Books))
    }

    @Test
    fun installedManifestCheckRejectsMissingOrChangedPayload() {
        val bytes = "catalog".toByteArray()
        val manifest = manifest(bytes)
        val installer = CategoryPackInstaller(temporaryFolder.newFolder("verified-pack"))
        val installed = installer.install(manifest) { ByteArrayInputStream(bytes) }

        assertTrue(installer.matchesInstalledManifest(manifest))
        File(installed, manifest.files.single().name).writeText("different length")
        assertFalse(installer.matchesInstalledManifest(manifest))
    }

    @Test
    fun removedBundledCoverDoesNotInvalidateVerifiedCatalog() {
        val catalog = "catalog".toByteArray()
        val cover = "image".toByteArray()
        val base = manifest(catalog)
        val coverSpec = CategoryPackFileSpec(
            "covers/top.jpg",
            cover.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(cover)
                .joinToString("") { "%02x".format(it) }
        )
        val combined = base.copy(
            installedBytes = catalog.size.toLong() + cover.size,
            files = base.files + coverSpec
        )
        val installer = CategoryPackInstaller(temporaryFolder.newFolder("removable-cover"))
        val installed = installer.install(combined) { spec ->
            ByteArrayInputStream(if (spec.name.startsWith("covers/")) cover else catalog)
        }
        assertTrue(File(installed, coverSpec.name).delete())

        assertTrue(installer.matchesInstalledManifest(combined))
    }

    @Test
    fun repeatedInstallAfterUncommittedReplacementPreservesLastKnownGoodPack() {
        val root = temporaryFolder.newFolder("repeated-replacement")
        val installer = CategoryPackInstaller(root)
        val original = "known-good".toByteArray()
        val promoted = "not-yet-committed".toByteArray()
        val broken = "broken-third-attempt".toByteArray()
        installer.install(manifest(original)) { ByteArrayInputStream(original) }
        installer.install(manifest(promoted), retainPreviousUntilCommit = true) {
            ByteArrayInputStream(promoted)
        }

        val invalid = manifest(broken).copy(
            files = listOf(manifest(broken).files.single().copy(sha256 = "0".repeat(64)))
        )
        assertTrue(runCatching { installer.install(invalid) { ByteArrayInputStream(broken) } }.isFailure)

        val installed = requireNotNull(installer.installedDirectory(MediaType.BOOK))
        assertArrayEquals(original, File(installed, "catalog/book_catalog.jsonl.gzip").readBytes())
        assertFalse(File(root, ".books.previous").exists())
    }

    private fun manifest(bytes: ByteArray): CategoryPackInstallManifest = CategoryPackInstallManifest(
        packId = OfficialPackIds.Books,
        snapshot = "2026-09-30",
        downloadBytes = bytes.size.toLong(),
        installedBytes = bytes.size.toLong(),
        files = listOf(
            CategoryPackFileSpec(
                "catalog/book_catalog.jsonl.gzip",
                bytes.size.toLong(),
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            )
        ),
        encodedManifest = """{"schemaVersion":1,"packId":"books","displayName":"Books","version":"2026-09-30","minRankOfflineVersion":"0.2.0","provider":{"id":"openlibrary","name":"Open Library"},"capabilities":{"search":true,"filters":true,"covers":true,"popularity":true,"merge":false,"ratings":true},"filters":[{"id":"subject","field":"subject","type":"multi_select","order":1}],"subtitle":["subject"],"merge":{"enabled":false,"strategy":"NONE"},"defaults":{"showImages":true,"stickyScoreHeader":false,"mergeEnabled":false,"ratingMode":"simple"},"integrity":{"installedBytes":${bytes.size},"files":[{"path":"catalog/book_catalog.jsonl.gzip","bytes":${bytes.size},"sha256":"${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"}]}}"""
    )
}
