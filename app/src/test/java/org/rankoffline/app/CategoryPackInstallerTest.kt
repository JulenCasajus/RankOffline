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
