package org.rankoffline.app

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.optionalLogo(
    key: String,
    declared: Map<String, PackManifestFile>
): String? {
    if (!has(key) || isNull(key)) return null
    val path = requiredAttributionText(key, 256)
    PackManifestParser.validateDataPath(path)
    require(path.startsWith("metadata/attribution/")) { "Source logos must be in metadata/attribution/" }
    require(listOf(".png", ".jpg", ".jpeg", ".webp").any { path.endsWith(it, true) }) {
        "Unsupported attribution logo format"
    }
    val bytes = declared[path]?.bytes ?: throw IllegalArgumentException("Undeclared source logo: $path")
    require(bytes in 1..(2L * 1024L * 1024L)) { "Attribution logo is empty or too large" }
    return path
}

internal fun validateLegalDocument(
    path: String,
    declared: Map<String, PackManifestFile>
): String {
    PackManifestParser.validateDataPath(path)
    require(path.startsWith("metadata/legal/")) { "Legal documents must be in metadata/legal/" }
    require(path.endsWith(".txt", ignoreCase = true)) { "Legal documents must be plain text" }
    val bytes = declared[path]?.bytes
        ?: throw IllegalArgumentException("Undeclared legal document: $path")
    require(bytes in 1..PackAttributionParser.MAX_DOCUMENT_BYTES) { "Legal document is empty or too large" }
    return path
}

internal fun JSONObject.requiredArray(key: String): JSONArray = getJSONArray(key)

internal fun JSONArray.objects(limit: Int): List<JSONObject> {
    require(length() <= limit) { "Too many attribution entries" }
    return List(length()) { getJSONObject(it) }
}

internal fun JSONArray.attributionStrings(limit: Int): List<String> {
    require(length() <= limit) { "Too many attribution entries" }
    return List(length()) { index ->
        require(get(index) is String) { "Expected text in attribution array" }
        getString(index)
    }
}

internal fun JSONArray.ids(limit: Int): List<String> = attributionStrings(limit).also { ids ->
    require(ids.distinct().size == ids.size) { "Duplicate attribution reference" }
    require(ids.all { it.matches(PackAttributionParser.ID) }) { "Invalid attribution reference" }
}
