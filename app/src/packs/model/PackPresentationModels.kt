package org.rankoffline.app

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
    val allowedHosts: Set<String>,
    /** Optional data-only metadata lookup; no provider or category logic lives in the APK. */
    val metadataRequest: PackCoverMetadataRequest? = null
)

data class PackCoverMetadataRequest(
    val referenceKey: String,
    val method: String,
    val urlTemplate: String,
    val bodyTemplate: String?,
    val imageJsonPaths: List<String>,
    val requestIntervalMillis: Long
)

