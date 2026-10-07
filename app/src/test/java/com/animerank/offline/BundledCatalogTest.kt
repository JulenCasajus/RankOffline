package com.animerank.offline

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledCatalogTest {
    @Test
    fun bundledCatalogContainsUniqueIdsAndAnyPopularitySnapshotIsComplete() {
        val entries = Json.parseToJsonElement(catalogFile().readText()).jsonArray
        val ids = entries.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        val ranked = entries.mapNotNull { entry ->
            val item = entry.jsonObject
            item["popularityRank"]?.jsonPrimitive?.intOrNull?.let { rank ->
                rank to item["popularity"]?.jsonPrimitive?.intOrNull
            }
        }

        assertEquals(40_744, entries.size)
        assertEquals(entries.size, ids.distinct().size)
        if (ranked.isNotEmpty()) {
            assertEquals(5_000, ranked.size)
            assertEquals((1..5_000).toList(), ranked.map { it.first }.sorted())
            assertTrue(ranked.all { it.second != null })
        }
    }

    @Test
    fun bundledCatalogHashMatchesVersionAsset() {
        val catalog = catalogFile().readBytes()
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(catalog)
            .joinToString("") { "%02x".format(it) }

        assertEquals(versionFile().readText().trim(), actual)
    }

    private fun catalogFile() = locateAsset("anime_catalog.json")
    private fun versionFile() = locateAsset("anime_catalog.version")

    private fun locateAsset(name: String): File = listOf(
        File("src/main/assets/$name"),
        File("app/src/main/assets/$name")
    ).firstOrNull(File::isFile) ?: error("Unable to locate bundled asset $name")
}
