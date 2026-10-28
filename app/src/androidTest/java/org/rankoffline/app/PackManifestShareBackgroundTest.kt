package org.rankoffline.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackManifestShareBackgroundTest {
    @Test
    fun optionalShareBackgroundIsIntegrityDeclaredAndOldPacksRemainCompatible() {
        assertNull(PackManifestParser.parse(manifestJson()).shareBackground)
        assertEquals(
            "metadata/share_background.webp",
            PackManifestParser.parse(
                manifestJson(
                    sharePath = "metadata/share_background.webp",
                    extraFiles = listOf("metadata/share_background.webp" to 128L)
                )
            ).shareBackground
        )
    }

    @Test
    fun unsafeMissingAndOversizedShareBackgroundsAreRejected() {
        listOf(
            manifestJson(sharePath = "../escape.webp"),
            manifestJson(sharePath = "metadata/missing.webp"),
            manifestJson(
                sharePath = "metadata/share_background.webp",
                extraFiles = listOf("metadata/share_background.webp" to PackManifestParser.MAX_SHARE_BACKGROUND_BYTES + 1)
            )
        ).forEach { encoded -> assertTrue(runCatching { PackManifestParser.parse(encoded) }.isFailure) }
    }

    private fun manifestJson(
        sharePath: String? = null,
        extraFiles: List<Pair<String, Long>> = emptyList()
    ): String {
        val files = listOf("catalog/items.jsonl.gzip" to 1L) + extraFiles
        return JSONObject().apply {
            put("schemaVersion", 1); put("packId", "movies"); put("displayName", "Movies")
            put("version", "fixture"); put("minRankOfflineVersion", "0.2.0")
            put("provider", JSONObject().put("id", "fixture").put("name", "Fixture"))
            put("coverSources", JSONArray()); put("catalog", JSONArray().put("catalog/items.jsonl.gzip"))
            put("groups", JSONArray()); put("covers", JSONArray())
            put("capabilities", JSONObject().apply {
                put("search", true); put("filters", true); put("covers", true)
                put("popularity", true); put("merge", false); put("ratings", true)
            })
            put("filters", JSONArray()); put("subtitle", JSONArray())
            put("merge", JSONObject().put("enabled", false).put("strategy", "NONE"))
            put("defaults", JSONObject().apply {
                put("showImages", true); put("stickyScoreHeader", true)
                put("mergeEnabled", false); put("ratingMode", "simple")
            })
            put("integrity", JSONObject().apply {
                put("installedBytes", files.sumOf { it.second })
                put("files", JSONArray().apply {
                    files.forEach { (path, bytes) ->
                        put(JSONObject().put("path", path).put("bytes", bytes).put("sha256", "0".repeat(64)))
                    }
                })
            })
            if (sharePath != null) put("presentation", JSONObject().put("shareBackground", sharePath))
        }.toString()
    }
}
