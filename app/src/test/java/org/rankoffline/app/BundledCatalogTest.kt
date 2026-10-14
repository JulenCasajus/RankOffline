package org.rankoffline.app

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledCatalogTest {
    @Test
    fun bundledCatalogContainsUniqueIdsAndCompletePopularitySnapshot() {
        val entries = Json.parseToJsonElement(catalogFile().readText()).jsonArray
        val ids = entries.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        val ranked = entries.mapNotNull { entry ->
            val item = entry.jsonObject
            item["popularityRank"]?.jsonPrimitive?.intOrNull?.let { rank ->
                rank to item["popularity"]?.jsonPrimitive?.intOrNull
            }
        }

        assertEquals(40_742, entries.size)
        assertEquals(entries.size, ids.distinct().size)
        assertEquals(5_000, ranked.size)
        assertEquals((1..5_000).toList(), ranked.map { it.first }.sorted())
        assertTrue(ranked.all { it.second != null })
    }

    @Test
    fun bundledCatalogHashMatchesVersionAsset() {
        val catalog = catalogFile().readBytes()
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(catalog)
            .joinToString("") { "%02x".format(it) }

        assertEquals(versionFile().readText().trim(), actual)
    }

    @Test
    fun bundledCatalogContainsQueryableAniListMetadata() {
        val entries = Json.parseToJsonElement(catalogFile().readText()).jsonArray
        val objects = entries.map { it.jsonObject }

        assertTrue(objects.all { it["genres"]?.jsonArray != null })
        assertTrue(objects.all { it["studios"]?.jsonArray != null })
        assertTrue(objects.count { it.getValue("genres").jsonArray.isNotEmpty() } > 10_000)
        assertTrue(objects.count { it["format"]?.jsonPrimitive?.contentOrNull != null } > 10_000)
        assertTrue(objects.count { it.getValue("studios").jsonArray.isNotEmpty() } > 5_000)
    }

    @Test
    fun bundledCatalogGroupsHaveMultipleMembersAndStableRoots() {
        val entries = Json.parseToJsonElement(catalogFile().readText()).jsonArray.map { it.jsonObject }
        val ids = entries.map { it.getValue("id").jsonPrimitive.content }.toSet()
        val grouped = entries.filter { it["groupId"]?.jsonPrimitive?.contentOrNull != null }
        val groupCounts = grouped.map { it.getValue("groupId").jsonPrimitive.content }.groupingBy { it }.eachCount()
        assertTrue(groupCounts.isNotEmpty())
        assertTrue(groupCounts.values.all { it > 1 })
        assertTrue(grouped.all { entry ->
            val groupId = entry.getValue("groupId").jsonPrimitive.content
            val format = entry.getValue("groupFormat").jsonPrimitive.content
            val familyRoot = entry.getValue("groupFamilyRootId").jsonPrimitive.content
            val cover = entry.getValue("groupCoverAnimeId").jsonPrimitive.content
            groupId == "group:$familyRoot:$format" && familyRoot in ids && cover in ids
        })
        assertTrue(grouped.any { it.getValue("groupFormat").jsonPrimitive.content == "SERIES" })
        assertTrue(grouped.any { it.getValue("groupFormat").jsonPrimitive.content == "MOVIES" })
        assertTrue(grouped.all {
            it.getValue("groupFormat").jsonPrimitive.content in setOf("SERIES", "MOVIES", "SPECIAL", "MUSIC")
        })
    }

    @Test
    fun bundledCatalogDoesNotMergeOnePieceAndDragonBallFamilies() {
        val entries = Json.parseToJsonElement(catalogFile().readText()).jsonArray
            .associate { entry ->
                val item = entry.jsonObject
                item.getValue("id").jsonPrimitive.content to item
            }
        val onePieceGroup = entries.getValue("mal:21")["groupId"]?.jsonPrimitive?.contentOrNull
        val dragonBallGroup = entries.getValue("mal:223")["groupId"]?.jsonPrimitive?.contentOrNull
        val monstersGroup = entries.getValue("mal:56055")["groupId"]?.jsonPrimitive?.contentOrNull

        assertTrue(dragonBallGroup != null)
        assertTrue(onePieceGroup != dragonBallGroup)
        assertTrue(monstersGroup == null || onePieceGroup != monstersGroup)
    }

    @Test
    fun bundledCatalogSeparatesNarutoAndBorutoSeries() {
        val entries = Json.parseToJsonElement(catalogFile().readText()).jsonArray
            .associate { entry ->
                val item = entry.jsonObject
                item.getValue("id").jsonPrimitive.content to item
            }
        fun group(id: String) = entries.getValue(id)["groupId"]?.jsonPrimitive?.contentOrNull

        val naruto = group("mal:20")
        val shippuden = group("mal:1735")
        val boruto = group("mal:34566")
        val borutoPart2 = group("mal:54687")

        assertTrue(naruto != null)
        assertEquals(naruto, shippuden)
        assertTrue(boruto != null)
        assertEquals(boruto, borutoPart2)
        assertTrue(naruto != boruto)
    }

    private fun catalogFile() = locateAsset("anime_catalog.json")
    private fun versionFile() = locateAsset("anime_catalog.version")

    private fun locateAsset(name: String): File = listOf(
        File("src/main/assets/$name"),
        File("app/src/main/assets/$name")
    ).firstOrNull(File::isFile) ?: error("Unable to locate bundled asset $name")
}
