package org.rankoffline.app

import org.json.JSONArray
import org.json.JSONObject

/** Shared strict JSON and path primitives for the v2 manifest parsers. */
internal object PackManifestJson {
    private const val MAX_PATH_LENGTH = 256
    private val FIELD_ID = Regex("[a-z][a-z0-9_]{0,63}")
    val LOCALE_TAG = Regex("[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})*")
    private val FORBIDDEN_SUFFIXES = setOf(
        ".dex", ".jar", ".class", ".so", ".apk", ".js", ".html", ".htm", ".svg", ".sh"
    )

    fun validateDataPath(path: String) {
        require(path.length in 1..MAX_PATH_LENGTH && !path.startsWith('/') && '\\' !in path)
        require(path.none(Char::isISOControl) && ':' !in path)
        val components = path.split('/')
        require(components.size >= 2 && components.none { it.isBlank() || it == "." || it == ".." })
        require(components.first() in setOf("catalog", "groups", "covers", "metadata")) {
            "Payload is outside supported data directories"
        }
        require(FORBIDDEN_SUFFIXES.none { path.endsWith(it, ignoreCase = true) }) {
            "Executable or active payload is forbidden"
        }
    }

    fun requireFieldId(value: String) {
        require(value.matches(FIELD_ID)) { "Invalid field ID: $value" }
    }
}

internal fun JSONObject.requireOnlyKeys(vararg names: String) {
    val supported = names.toSet()
    val unknown = keys().asSequence().filterNot { it in supported }.toList()
    require(unknown.isEmpty()) { "Unsupported manifest properties: $unknown" }
}

internal fun JSONObject.requiredText(key: String, limit: Int): String =
    getString(key).also { require(get(key) is String && it.isValidManifestText(limit)) }

internal fun JSONObject.optionalText(key: String, limit: Int): String? =
    if (!has(key) || isNull(key)) null else requiredText(key, limit)

internal fun JSONObject.localizedNames(): Map<String, String> {
    require(length() <= 64) { "Too many localized labels" }
    return keys().asSequence().associateWith { key ->
        require(key.matches(PackManifestJson.LOCALE_TAG)) { "Invalid language tag: $key" }
        requiredText(key, 160)
    }
}

internal fun JSONObject.optionalPath(
    key: String,
    declared: Map<String, PackManifestFile>,
    prefix: String,
    maxBytes: Long,
    suffixes: Set<String>
): String? = optionalText(key, 256)?.also { path ->
    PackManifestJson.validateDataPath(path)
    require(path.startsWith(prefix) && suffixes.any { path.endsWith(it, ignoreCase = true) }) {
        "Invalid image path: $path"
    }
    require(declared[path]?.bytes in 1..maxBytes) { "Undeclared or oversized image: $path" }
}

internal fun String.isValidManifestText(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && none(Char::isISOControl)

internal fun JSONArray.objects(): List<JSONObject> = List(length()) { index -> getJSONObject(index) }

internal fun JSONArray.strings(maxItems: Int = 100_000): List<String> {
    require(length() <= maxItems) { "Too many array entries" }
    return List(length()) { index ->
        require(get(index) is String) { "Expected string array" }
        getString(index)
    }
}
