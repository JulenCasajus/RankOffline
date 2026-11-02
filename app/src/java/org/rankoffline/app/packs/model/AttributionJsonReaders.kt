package org.rankoffline.app

import org.json.JSONObject

internal fun JSONObject.requireOnlyAttributionKeys(vararg names: String) {
    val allowed = names.toSet()
    val unknown = keys().asSequence().filterNot { it in allowed }.toList()
    require(unknown.isEmpty()) { "Unsupported attribution properties: $unknown" }
}

internal fun JSONObject.requiredId(key: String): String =
    requiredAttributionText(key, 64).also {
        require(it.matches(PackAttributionParser.ID)) { "Invalid attribution ID: $it" }
    }

internal fun JSONObject.requiredAttributionText(key: String, maxLength: Int): String {
    require(get(key) is String) { "Expected text for $key" }
    return getString(key).trim().also { text ->
        require(text.isNotEmpty() && text.length <= maxLength)
        require(text.none { it.isISOControl() }) { "Control character in $key" }
    }
}

internal fun JSONObject.optionalUrl(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val url = requiredAttributionText(key, 2048)
    require(AttributionUrlPolicy.isSafeExternalHttpsUrl(url)) { "Unsafe attribution URL" }
    return url
}

internal fun JSONObject.optionalDocument(
    key: String,
    declared: Map<String, PackManifestFile>
): String? = if (!has(key) || isNull(key)) null
    else validateLegalDocument(requiredAttributionText(key, 256), declared)

internal fun JSONObject.notices(key: String): List<String> =
    if (!has(key)) emptyList() else requiredArray(key)
        .attributionStrings(PackAttributionParser.MAX_NOTICES).map { text ->
        require(text.isNotBlank() && text.length <= 4096) { "Invalid attribution notice" }
        require(text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
            "Unsafe control character in attribution notice"
        }
        text
    }

