package org.rankoffline.app

import org.json.JSONObject

/** Capabilities, generic merge behavior and first-install preference defaults. */
internal object PackManifestSettings {
    fun capabilities(root: JSONObject): PackCapabilities {
        root.requireOnlyKeys("search", "filters", "covers", "popularity", "merge", "ratings")
        return PackCapabilities(
            search = root.getBoolean("search"),
            filters = root.getBoolean("filters"),
            covers = root.getBoolean("covers"),
            popularity = root.getBoolean("popularity"),
            merge = root.getBoolean("merge"),
            ratings = root.getBoolean("ratings")
        )
    }

    fun merge(root: JSONObject, capabilities: PackCapabilities): PackMergeDefinition {
        root.requireOnlyKeys("enabled", "strategy", "groupType", "labels")
        val merge = PackMergeDefinition(
            enabled = root.getBoolean("enabled"),
            strategy = PackMergeStrategy.parse(root.getString("strategy")),
            groupType = root.optionalText("groupType", 64)?.also(PackManifestJson::requireFieldId),
            labels = root.optJSONObject("labels")?.localizedNames().orEmpty()
        )
        require(!merge.enabled ||
            (capabilities.merge && merge.strategy != PackMergeStrategy.NONE && merge.groupType != null)
        ) { "Enabled merge requires a supported strategy, group type and capability" }
        return merge
    }

    fun defaults(root: JSONObject, merge: PackMergeDefinition): PackDefaults {
        // Read old manifests without continuing to expose shared-cover behavior.
        root.requireOnlyKeys("showImages", "stickyScoreHeader", "mergeEnabled",
            "ratingMode", "oneCoverPerGroup")
        val defaults = PackDefaults(
            showImages = root.getBoolean("showImages"),
            stickyScoreHeader = root.getBoolean("stickyScoreHeader"),
            mergeEnabled = root.getBoolean("mergeEnabled"),
            ratingMode = root.getString("ratingMode").also {
                require(it == "simple" || it == "elaborated") { "Unsupported rating mode" }
            }
        )
        require(!defaults.mergeEnabled || merge.enabled) { "Default merge requires enabled merge" }
        return defaults
    }
}
