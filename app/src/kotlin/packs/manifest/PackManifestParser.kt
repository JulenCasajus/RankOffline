package org.rankoffline.app

import org.json.JSONObject

/** Public v2 parser facade. Keep callers independent from parsing implementation modules. */
object PackManifestParser {
    const val SCHEMA_VERSION = 2
    const val MAX_MANIFEST_BYTES = 2L * 1024L * 1024L
    const val MAX_COVER_INDEX_BYTES = 4L * 1024L * 1024L
    const val MAX_COVER_INDEX_ENTRIES = 100_000
    internal const val MAX_SHARE_BACKGROUND_BYTES = 8L * 1024L * 1024L

    fun parse(encoded: String): PackManifest {
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) {
            "Pack manifest is too large"
        }
        return parse(JSONObject(encoded))
    }

    fun parse(root: JSONObject): PackManifest {
        root.requireOnlyKeys("schemaVersion", "packId", "version", "minRankOfflineVersion", "displayName",
            "presentation", "capabilities", "fields", "filters", "subtitle", "merge", "defaults",
            "rating", "coverSources", "catalog", "groups", "covers", "attribution", "integrity")
        require(root.toString().toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) {
            "Pack manifest is too large"
        }
        val schemaVersion = root.getInt("schemaVersion")
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported pack schema: $schemaVersion" }
        val packId = PackId.fromJson(root.get("packId"))
        val displayName = root.requiredText("displayName", 160)
        val version = root.requiredText("version", 80)
        val minRankOfflineVersion = root.requiredText("minRankOfflineVersion", 80)

        val integrity = PackManifestIntegrity.parse(root.getJSONObject("integrity"))
        val presentation = PackManifestResources.presentation(
            root.getJSONObject("presentation"), integrity.declared
        )
        val fields = PackManifestSchema.fields(root.getJSONArray("fields"))
        val fieldsById = fields.associateBy { it.id }
        val capabilities = PackManifestSettings.capabilities(root.getJSONObject("capabilities"))
        val filters = PackManifestSchema.filters(root.getJSONArray("filters"), fieldsById, capabilities)
        val subtitle = PackManifestSchema.subtitle(root.getJSONArray("subtitle"), fieldsById)
        val merge = PackManifestSettings.merge(root.getJSONObject("merge"), capabilities)
        val defaults = PackManifestSettings.defaults(root.getJSONObject("defaults"), merge)
        val rating = PackManifestRating.parse(root, capabilities)
        require(capabilities.ratings || defaults.ratingMode == "simple") {
            "A pack without ratings cannot default to elaborate mode"
        }
        val payload = PackManifestIntegrity.sections(root, integrity.declared, merge, capabilities)
        val attribution = PackAttributionParser.parse(root.getJSONObject("attribution"), integrity.files)
        val coverSources = PackManifestResources.coverSources(root.getJSONArray("coverSources"), attribution, capabilities)

        return PackManifest(
            schemaVersion = schemaVersion,
            packId = packId,
            displayName = displayName,
            version = version,
            minRankOfflineVersion = minRankOfflineVersion,
            presentation = presentation,
            fields = fields,
            capabilities = capabilities,
            filters = filters,
            subtitle = subtitle,
            merge = merge,
            defaults = defaults,
            rating = rating,
            coverSources = coverSources,
            catalog = payload.catalog,
            groups = payload.groups,
            covers = payload.covers,
            attribution = attribution,
            files = integrity.files
        )
    }

    /** Public compatibility point used by installers, credits and cover index readers. */
    fun validateDataPath(path: String) = PackManifestJson.validateDataPath(path)
}
