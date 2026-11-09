package org.rankoffline.app

enum class PackFilterType(val wireValue: String) {
    MULTI_SELECT("multi_select"), RANGE("range");

    companion object {
        fun parse(value: String): PackFilterType = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unsupported filter type: $value")
    }
}

enum class PackMergeStrategy {
    NONE, GROUP_ID, COLLECTION_ID, RELATION_GRAPH;

    companion object {
        fun parse(value: String): PackMergeStrategy = entries.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Unsupported merge strategy: $value")
    }
}

data class PackCapabilities(
    val search: Boolean,
    val filters: Boolean,
    val covers: Boolean,
    val popularity: Boolean,
    val merge: Boolean,
    val ratings: Boolean
)

data class PackFilterDefinition(
    val id: String,
    val field: String,
    val type: PackFilterType,
    val order: Int,
    val labels: Map<String, String>
)

data class PackMergeDefinition(
    val enabled: Boolean,
    val strategy: PackMergeStrategy,
    val groupType: String?,
    val labels: Map<String, String>
)

data class PackDefaults(
    val showImages: Boolean,
    val stickyScoreHeader: Boolean,
    val mergeEnabled: Boolean,
    val ratingMode: String
)

data class PackManifestFile(val path: String, val bytes: Long, val sha256: String)

/** The single authoritative manifest for identity, contents, schema, presentation and integrity. */
data class PackManifest(
    val schemaVersion: Int,
    val packId: PackId,
    val displayName: String,
    val version: String,
    val minRankOfflineVersion: String,
    val presentation: PackPresentation,
    val fields: List<PackFieldDefinition>,
    val capabilities: PackCapabilities,
    val filters: List<PackFilterDefinition>,
    val subtitle: List<String>,
    val merge: PackMergeDefinition,
    val defaults: PackDefaults,
    val coverSources: List<PackCoverSource>,
    val catalog: List<String>,
    val groups: List<String>,
    val covers: List<String>,
    val attribution: PackAttributions,
    val files: List<PackManifestFile>,
    val rating: PackRatingDefinition? = null
) {
    val fieldSpecs: Map<String, CatalogFieldSpec>
        get() = fields.associate { it.id to it.toCatalogFieldSpec() }

    fun localizedName(language: String): String =
        presentation.names[language]
            ?: presentation.names[language.substringBefore('-')]
            ?: displayName
}
