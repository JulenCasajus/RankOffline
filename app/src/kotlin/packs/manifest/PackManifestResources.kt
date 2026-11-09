package org.rankoffline.app

import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Pack-specific UI assets and remote cover source declarations (no network requests). */
internal object PackManifestResources {
    private const val MAX_COVER_SOURCES = 64
    private val HEX_COLOR = Regex("#[0-9A-Fa-f]{6}")
    private val IMAGE_SUFFIXES = setOf(".png", ".jpg", ".jpeg", ".webp")

    fun presentation(
        root: JSONObject,
        declared: Map<String, PackManifestFile>
    ): PackPresentation {
        root.requireOnlyKeys("names", "accentColor", "shareBackgrounds")
        val backgrounds = root.optJSONObject("shareBackgrounds")
        backgrounds?.requireOnlyKeys("topSeven", "topTen")
        return PackPresentation(
            names = root.optJSONObject("names")?.localizedNames().orEmpty(),
            accentColor = root.getString("accentColor").also {
                require(it.matches(HEX_COLOR)) { "Invalid pack accent color" }
            }.uppercase(Locale.ROOT),
            shareBackgrounds = PackShareBackgrounds(
                topSeven = backgrounds?.optionalPath(
                    "topSeven", declared, "metadata/", PackManifestParser.MAX_SHARE_BACKGROUND_BYTES, IMAGE_SUFFIXES
                ),
                topTen = backgrounds?.optionalPath(
                    "topTen", declared, "metadata/", PackManifestParser.MAX_SHARE_BACKGROUND_BYTES, IMAGE_SUFFIXES
                )
            )
        )
    }

    fun coverSources(
        array: JSONArray,
        attribution: PackAttributions,
        capabilities: PackCapabilities
    ): List<PackCoverSource> {
        val sourceIds = attribution.sources.mapTo(hashSetOf()) { it.id }
        require(array.length() <= MAX_COVER_SOURCES)
        val sources = array.objects().map { source ->
            source.requireOnlyKeys("id", "sourceId", "providerId", "urlTemplates", "allowedHosts",
                "metadataRequest")
            val hosts = source.getJSONArray("allowedHosts").strings(16)
                .map { it.lowercase(Locale.ROOT) }
            require(hosts.isNotEmpty() && hosts.distinct().size == hosts.size)
            val allowedHosts = hosts.toSet()
            require(allowedHosts.all { AttributionUrlPolicy.isSafeExternalHttpsUrl("https://$it/") }) {
                "Invalid allowed image host"
            }
            val metadata = source.optJSONObject("metadataRequest")?.toCoverMetadataRequest()
            val templates = source.getJSONArray("urlTemplates").strings(8)
            require((metadata == null) != templates.isEmpty()) {
                "Cover source must declare either image URL templates or a metadata request"
            }
            templates.forEach { validatePackCoverUrlTemplate(it, allowedHosts) }
            PackCoverSource(
                id = source.getString("id").also(PackManifestJson::requireFieldId),
                sourceId = source.getString("sourceId").also {
                    require(it in sourceIds) { "Unknown cover attribution source: $it" }
                },
                providerId = source.optionalText("providerId", 64)?.also(PackManifestJson::requireFieldId),
                urlTemplates = templates,
                allowedHosts = allowedHosts,
                metadataRequest = metadata
            )
        }
        require(sources.map { it.id }.distinct().size == sources.size) { "Duplicate cover source IDs" }
        require(capabilities.covers || sources.isEmpty()) { "Remote covers declared with covers disabled" }
        return sources
    }
}

private fun JSONObject.toCoverMetadataRequest(): PackCoverMetadataRequest {
    requireOnlyKeys("referenceKey", "method", "urlTemplate", "bodyTemplate",
        "imageJsonPaths", "requestIntervalMillis")
    val method = requiredText("method", 16)
    require(method == "GET" || method == "POST_JSON") { "Unsupported metadata request method" }
    val urlTemplate = requiredText("urlTemplate", 2_048)
    validateMetadataUrlTemplate(urlTemplate)
    val bodyTemplate = optionalText("bodyTemplate", 8_192)
    require((method == "POST_JSON") == (bodyTemplate != null)) { "Invalid metadata request body" }
    bodyTemplate?.let {
        require("{reference}" in it && runCatching {
            JSONObject(it.replace("{reference}", "1"))
        }.isSuccess) { "Invalid metadata JSON body template" }
    }
    require("{reference}" in urlTemplate || bodyTemplate?.contains("{reference}") == true) {
        "Metadata request does not use its reference"
    }
    val paths = getJSONArray("imageJsonPaths").strings(8)
    require(paths.isNotEmpty() && paths.distinct().size == paths.size &&
        paths.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")) }) {
        "Invalid metadata image JSON paths"
    }
    val rawInterval = get("requestIntervalMillis")
    require(rawInterval is Int || rawInterval is Long) { "Metadata interval must be an integer" }
    val interval = (rawInterval as Number).toLong()
    require(interval in 250L..60_000L) { "Invalid metadata request interval" }
    return PackCoverMetadataRequest(
        referenceKey = requiredText("referenceKey", 64).also(PackManifestJson::requireFieldId),
        method = method,
        urlTemplate = urlTemplate,
        bodyTemplate = bodyTemplate,
        imageJsonPaths = paths,
        requestIntervalMillis = interval
    )
}

private fun validateMetadataUrlTemplate(template: String) {
    require(template.countOccurrences("{reference}") <= 1)
    val authority = template.removePrefix("https://").takeWhile { it != '/' && it != '?' && it != '#' }
    require('{' !in authority && '}' !in authority) { "Dynamic metadata host is forbidden" }
    val substituted = template.replace("{reference}", "1")
    require('{' !in substituted && '}' !in substituted &&
        AttributionUrlPolicy.isSafeExternalHttpsUrl(substituted)) { "Unsafe metadata request URL" }
    val uri = URI(substituted)
    require(uri.rawFragment == null && uri.normalize().rawPath == uri.rawPath) {
        "Invalid metadata request URL"
    }
}

private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }

/** Validate syntax; downloaders must still encode references and prevent SSRF. */
internal fun validatePackCoverUrlTemplate(template: String, hosts: Set<String>) {
    require(template.length in 1..2048 && template.none(Char::isISOControl))
    require(template.startsWith("https://")) { "Image template must use HTTPS" }
    val authority = template.removePrefix("https://").takeWhile { it != '/' && it != '?' && it != '#' }
    require('{' !in authority && '}' !in authority) { "Dynamic image host is forbidden" }
    val substituted = template.replace("{reference}", "fixture")
        .replace("{providerId}", "fixture")
    require('{' !in substituted && '}' !in substituted) { "Unsupported image URL placeholder" }
    require(AttributionUrlPolicy.isSafeExternalHttpsUrl(substituted)) { "Unsafe image URL" }
    val uri = URI(substituted)
    require(uri.host.lowercase(Locale.ROOT) in hosts && uri.rawFragment == null &&
        uri.normalize().rawPath == uri.rawPath
    ) { "Unapproved image URL host, fragment or path" }
}
