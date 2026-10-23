package org.rankoffline.app

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
}
