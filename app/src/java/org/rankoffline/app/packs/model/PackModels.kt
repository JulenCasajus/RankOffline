package org.rankoffline.app

/** Stable, positive 64-bit identifier. 0 is reserved for the virtual RankOfflineAll scope.
 * Names of packs and publishers are presentation, never identity.
 */
@JvmInline
value class PackId(val value: Long) {
    init { require(value > 0L) { "A physical pack ID must be positive: $value" } }

    /** Canonical on-disk name for a physical installation under files/packs/. */
    fun directoryName(): String = "pack-$value"

    companion object {
        /** Only canonical pack-<positive decimal> directory names are valid. */
        fun fromDirectoryName(name: String): PackId? {
            if (!name.startsWith("pack-")) return null
            return runCatching { parse(name.removePrefix("pack-")) }.getOrNull()
        }

        fun parse(value: String): PackId {
            val number = value.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid numeric pack ID: $value")
            require(number > 0L && value == number.toString()) {
                "Pack ID must be a canonical positive decimal: $value"
            }
            return PackId(number)
        }
        /** A JSON manifest must contain an integer NUMBER, never a quoted string or a decimal. */
        fun fromJson(value: Any): PackId {
            require(value is Int || value is Long) { "packId must be a JSON integer" }
            return PackId((value as Number).toLong())
        }
    }

    override fun toString(): String = value.toString()
}

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

enum class PackShareTemplate { TOP_SEVEN, TOP_TEN }

data class PackShareBackgrounds(val topSeven: String?, val topTen: String?) {
    fun forTemplate(template: PackShareTemplate): String? = when (template) {
        PackShareTemplate.TOP_SEVEN -> topSeven
        PackShareTemplate.TOP_TEN -> topTen
    }
}

data class PackPresentation(
    val names: Map<String, String>,
    /** Canonical #RRGGBB; its meaning is visual, never an identity. */
    val accentColor: String,
    val shareBackgrounds: PackShareBackgrounds
)

data class PackFieldDefinition(
    val id: String,
    val type: CatalogFieldType,
    val multiple: Boolean,
    val labels: Map<String, String>,
    val options: Set<String>
) {
    fun toCatalogFieldSpec(): CatalogFieldSpec =
        CatalogFieldSpec(id = id, type = type, multiple = multiple, allowedValues = options)
}

/** Data-only image source; a sourceId refers to attribution.sources, not to Kotlin providers. */
data class PackCoverSource(
    val id: String,
    val sourceId: String,
    val providerId: String?,
    val urlTemplates: List<String>,
    val allowedHosts: Set<String>
)

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
