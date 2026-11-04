package org.rankoffline.app

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal object TestFixtures {
    val catalogBytes = "catalog fixture".toByteArray()
    val groupBytes = "group fixture".toByteArray()

    fun manifestJson(
        packId: Long = 11L,
        merge: Boolean = false,
        covers: Boolean = true,
        catalog: ByteArray = catalogBytes,
        groups: ByteArray = groupBytes
    ): String {
        val files = JSONArray().put(file("catalog/items.jsonl", catalog))
        val groupPaths = JSONArray()
        if (merge) {
            files.put(file("groups/groups.jsonl", groups))
            groupPaths.put("groups/groups.jsonl")
        }
        val installedBytes = catalog.size.toLong() + if (merge) groups.size else 0
        return JSONObject()
            .put("schemaVersion", 2)
            .put("packId", packId)
            .put("version", "1.0.0")
            .put("minRankOfflineVersion", "0.2.2")
            .put("displayName", "Fixture Pack")
            .put("presentation", JSONObject()
                .put("names", JSONObject().put("en", "Fixture Pack"))
                .put("accentColor", "#123ABC")
                .put("shareBackgrounds", JSONObject()))
            .put("capabilities", JSONObject()
                .put("search", true).put("filters", true).put("covers", covers)
                .put("popularity", true).put("merge", merge).put("ratings", true))
            .put("fields", JSONArray().put(JSONObject()
                .put("id", "genre").put("type", "choice").put("multiple", true)
                .put("labels", JSONObject().put("en", "Genre"))
                .put("options", JSONArray().put("drama").put("comedy"))).put(JSONObject()
                .put("id", "year").put("type", "integer").put("multiple", false)
                .put("labels", JSONObject().put("en", "Year"))))
            .put("filters", JSONArray().put(JSONObject()
                .put("id", "genre").put("field", "genre").put("type", "multi_select")
                .put("order", 0).put("labels", JSONObject().put("en", "Genre"))).put(JSONObject()
                .put("id", "year").put("field", "year").put("type", "range")
                .put("order", 1).put("labels", JSONObject().put("en", "Year"))))
            .put("subtitle", JSONArray().put("genre").put("year"))
            .put("merge", JSONObject().put("enabled", merge)
                .put("strategy", if (merge) "RELATION_GRAPH" else "NONE")
                .put("groupType", if (merge) "relations" else JSONObject.NULL)
                .put("labels", JSONObject().put("en", "Relations")))
            .put("defaults", JSONObject().put("showImages", covers)
                .put("stickyScoreHeader", true).put("mergeEnabled", merge)
                .put("ratingMode", "simple"))
            .put("rating", ratingJson())
            .put("coverSources", if (covers) JSONArray().put(coverSource()) else JSONArray())
            .put("catalog", JSONArray().put("catalog/items.jsonl"))
            .put("groups", groupPaths)
            .put("covers", JSONArray())
            .put("attribution", attributionJson())
            .put("integrity", JSONObject().put("installedBytes", installedBytes).put("files", files))
            .toString()
    }

    fun manifest(packId: Long = 11L, merge: Boolean = false): PackManifest =
        PackManifestParser.parse(manifestJson(packId, merge))

    fun ratingDefinition(): PackRatingDefinition = PackRatingDefinition(
        RatingSystemConfig(listOf(
            RatingCategory("writing", "Writing", "", 60.0, "#112233", 0),
            RatingCategory("visuals", "Visuals", "", 40.0, "#445566", 1)
        )),
        RatingProfilesConfig(listOf(
            RatingProfile("general", "General", mapOf("writing" to 60.0, "visuals" to 40.0))
        ))
    )

    fun backup(scopeId: Long = ViewingScope.ALL_ID): PersonalDataBackup = PersonalDataBackup(
        scopeId = scopeId,
        exportedAt = 123L,
        global = if (scopeId == ViewingScope.ALL_ID) BackupGlobalPreferences(
            allViewSettings = AllViewSettings(),
            themeSelection = AppThemePalette.MIDNIGHT_NEON.persistedValue,
            themes = BackupThemeWorkspace(),
            languageTag = "en",
            appIcon = AppIcon.DARK.persistedValue
        ) else null,
        packs = listOf(BackupLocalPack(if (scopeId == 0L) 11L else scopeId)),
        ratings = listOf(BackupRating(
            packId = if (scopeId == 0L) 11L else scopeId,
            canonicalId = "item:1",
            additiveScore = 0.5,
            qualityScore = 8.0,
            finalScore = 8.5,
            notes = "note",
            status = "completed",
            updatedAt = 5L,
            profileId = "general",
            categoryScores = listOf(BackupCategoryScore("writing", 8.0, true))
        )),
        groupRatings = emptyList()
    )

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun file(path: String, bytes: ByteArray) = JSONObject()
        .put("path", path).put("bytes", bytes.size).put("sha256", sha256(bytes))

    private fun coverSource() = JSONObject()
        .put("id", "fixture_cover").put("sourceId", "fixture")
        .put("providerId", "fixture").put("urlTemplates", JSONArray()
            .put("https://images.example.com/{reference}.jpg"))
        .put("allowedHosts", JSONArray().put("images.example.com"))

    private fun ratingJson(): JSONObject {
        val category = JSONObject().put("id", "writing").put("name", "Writing")
            .put("description", "Writing quality").put("weight", 100).put("color", "#112233")
            .put("order", 0).put("active", true)
        val profile = JSONObject().put("id", "general").put("name", "General")
            .put("weights", JSONObject().put("writing", 100))
        return JSONObject().put("categories", JSONArray().put(category))
            .put("profiles", JSONArray().put(profile))
    }

    private fun attributionJson() = JSONObject()
        .put("publisher", JSONObject().put("name", "Fixture Publisher"))
        .put("sources", JSONArray().put(JSONObject().put("id", "fixture")
            .put("name", "Fixture Source").put("notices", JSONArray())))
        .put("licences", JSONArray()).put("datasets", JSONArray())
        .put("notices", JSONArray()).put("legalFiles", JSONArray())
}
