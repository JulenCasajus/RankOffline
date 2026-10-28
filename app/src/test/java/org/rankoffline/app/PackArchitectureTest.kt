package org.rankoffline.app

import java.io.IOException
import java.util.zip.ZipException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackArchitectureTest {
    @Test
    fun officialPackIdsAreStableAndRoundTripThroughTemporaryMediaAdapter() {
        assertEquals(
            setOf("animes", "mangas", "movies", "series", "books"),
            MediaType.entries.map { it.toPackId().value }.toSet()
        )
        MediaType.entries.forEach { mediaType ->
            assertEquals(mediaType, mediaType.toPackId().toOfficialMediaTypeOrNull())
        }
        assertNull(PackId("community-books").toOfficialMediaTypeOrNull())
    }

    @Test
    fun executableAndTraversalPayloadsAreRejected() {
        val accepted = listOf(
            "catalog/items.jsonl.gzip",
            "groups/groups.jsonl.gzip",
            "covers/item.webp",
            "metadata/cover_index.json"
        )
        accepted.forEach(PackManifestParser::validateDataPath)
        listOf("../escape", "catalog/../../escape", "/absolute", "catalog/plugin.dex", "metadata/code.jar")
            .forEach { path -> assertTrue(runCatching { PackManifestParser.validateDataPath(path) }.isFailure) }
    }

    @Test
    fun appVersionCompatibilityIgnoresDebugSuffixButNotOlderCore() {
        assertTrue(isCompatibleAppVersion("0.2.0", "0.2.0-debug"))
        assertTrue(isCompatibleAppVersion("0.2.0", "0.2.1"))
        assertTrue(!isCompatibleAppVersion("0.2.0", "0.1.3"))
    }

    @Test
    fun archiveValidationSeparatesCorruptionFromInterruptedIo() {
        assertEquals(
            CategoryPackDownloadResult.INVALID_PACK,
            classifyArchiveValidationFailure(ZipException("corrupt"))
        )
        assertEquals(
            CategoryPackDownloadResult.NETWORK_ERROR,
            classifyArchiveValidationFailure(IOException("interrupted read"))
        )
        assertEquals(
            CategoryPackDownloadResult.INVALID_PACK,
            classifyArchiveValidationFailure(IllegalArgumentException("manifest mismatch"))
        )
    }

    @Test
    fun installedStorageUsesMeasuredFilesWithoutMixingLogicalPersonalEstimate() {
        val state = CategoryPackState(
            mediaType = MediaType.BOOK,
            origin = CategoryPackOrigin.DOWNLOADED,
            catalogBytes = 1_000L,
            downloadedCoverBytes = 300L,
            reclaimableBytes = 1_500L,
            retainedArchiveBytes = 200L,
            catalogDataBytes = 700L,
            groupDataBytes = 100L,
            metadataBytes = 200L,
            runtimeStatus = CategoryPackRuntimeStatus.READY
        )
        val personal = PackPersonalStorage(logicalDatabaseBytes = 80L, logicalPreferencesBytes = 20L)

        assertEquals(1_500L, state.installedBytes)
        assertEquals(100L, personal.logicalTotalBytes)
    }

}
