package org.rankoffline.app

import java.net.URI
import java.util.Locale
import org.json.JSONObject

/**
 * Attribution is metadata supplied by a pack, NOT a statement verified by RankOffline.
 * Never treat publisher names or URLs as trusted identities just because parsing succeeded.
 */
data class AttributionPublisher(
    val name: String,
    val url: String?
)

data class AttributionSource(
    val id: String,
    val name: String,
    val url: String?,
    val notices: List<String>,
    /** Optional integrity-declared logo belonging to this source. */
    val logoPath: String?
)

data class AttributionLicence(
    /** Identifier unique within this pack, e.g. "odbl-1.0". */
    val id: String,
    val name: String,
    val url: String?,
    /** SHA-256-declared local document inside metadata/legal/, if supplied. */
    val textPath: String?
)

data class AttributionDataset(
    val name: String,
    val sourceIds: List<String>,
    val licenceIds: List<String>,
    val notices: List<String>
)

data class PackAttributions(
    val publisher: AttributionPublisher,
    val sources: List<AttributionSource>,
    val licences: List<AttributionLicence>,
    val datasets: List<AttributionDataset>,
    val notices: List<String>,
    /** Additional declared documents such as image credits or third-party notices. */
    val legalFiles: List<String>
)

/**
 * Read the `attribution` object in a v2 .pack manifest.
 * Call this from PackManifestParser, using the complete manifest's verified file declarations.
 * No network requests are made: links remain passive text until the user explicitly opens one.
 */
object PackAttributionParser {
    internal const val MAX_ENTRIES = 64
    internal const val MAX_NOTICES = 32
    internal const val MAX_DOCUMENT_BYTES = 512L * 1024L
    internal val ID = Regex("[a-z][a-z0-9_-]{0,63}")

    fun parse(root: JSONObject, declaredFiles: Collection<PackManifestFile>): PackAttributions {
        root.requireOnlyAttributionKeys("publisher", "sources", "licences", "datasets", "notices", "legalFiles")
        val declared = declaredFiles.associateBy { it.path }
        require(declared.size == declaredFiles.size) { "Duplicate paths in pack integrity manifest" }

        val publisher = root.getJSONObject("publisher").let { json ->
            json.requireOnlyAttributionKeys("name", "url")
            AttributionPublisher(
                name = json.requiredAttributionText("name", 160),
                url = json.optionalUrl("url")
            )
        }

        val licences = root.requiredArray("licences").objects(MAX_ENTRIES).map { json ->
            json.requireOnlyAttributionKeys("id", "name", "url", "textPath")
            AttributionLicence(
                id = json.requiredId("id"),
                name = json.requiredAttributionText("name", 160),
                url = json.optionalUrl("url"),
                textPath = json.optionalDocument("textPath", declared)
            )
        }
        require(licences.map { it.id }.distinct().size == licences.size) {
            "Duplicate attribution licence ID"
        }

        val sources = root.requiredArray("sources").objects(MAX_ENTRIES).map { json ->
            json.requireOnlyAttributionKeys("id", "name", "url", "notices", "logoPath")
            AttributionSource(
                id = json.requiredId("id"),
                name = json.requiredAttributionText("name", 160),
                url = json.optionalUrl("url"),
                notices = json.notices("notices"),
                logoPath = json.optionalLogo("logoPath", declared)
            )
        }
        require(sources.map { it.id }.distinct().size == sources.size) {
            "Duplicate attribution source ID"
        }

        val sourceIds = sources.mapTo(hashSetOf()) { it.id }
        val licenceIds = licences.mapTo(hashSetOf()) { it.id }
        val datasets = root.requiredArray("datasets").objects(MAX_ENTRIES).map { json ->
            json.requireOnlyAttributionKeys("name", "sourceIds", "licenceIds", "notices")
            val referencedSources = json.requiredArray("sourceIds")
                .ids(MAX_ENTRIES).also { ids ->
                    require(ids.all(sourceIds::contains)) { "Dataset references an unknown attribution source" }
                }
            val referencedLicences = json.requiredArray("licenceIds")
                .ids(MAX_ENTRIES).also { ids ->
                    require(ids.all(licenceIds::contains)) { "Dataset references an unknown licence" }
                }
            AttributionDataset(
                name = json.requiredAttributionText("name", 160),
                sourceIds = referencedSources,
                licenceIds = referencedLicences,
                notices = json.notices("notices")
            )
        }

        val legalFiles = root.requiredArray("legalFiles").attributionStrings(MAX_ENTRIES)
            .map { validateLegalDocument(it, declared) }
        require(legalFiles.distinct().size == legalFiles.size) {
            "Duplicate legal document"
        }

        return PackAttributions(
            publisher = publisher,
            sources = sources,
            licences = licences,
            datasets = datasets,
            notices = root.notices("notices"),
            legalFiles = legalFiles
        )
    }


}

/**
 * Technical URL hygiene, NOT proof that a website is trustworthy or belongs to a named provider.
 * Community-pack links should additionally show their domain before the user leaves the app.
 */
object AttributionUrlPolicy {
    private val DOMAIN_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

    fun isSafeExternalHttpsUrl(value: String): Boolean {
        if (value.length !in 1..2048 || value != value.trim() || value.any(Char::isISOControl)) {
            return false
        }
        val uri = try { URI(value) } catch (_: Exception) { return false }
        if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host.length > 253 || host.endsWith('.') || !host.contains('.')) return false
        if (host.split('.').any { !it.matches(DOMAIN_LABEL) }) return false
        // No IP literals or private/intranet hostnames: these are external, user-visible links.
        if (host.all { it.isDigit() || it == '.' } || host.endsWith(".local")) return false
        return true
    }

    fun displayHost(url: String): String? =
        url.takeIf(::isSafeExternalHttpsUrl)?.let { URI(it).host.lowercase(Locale.ROOT) }
}
