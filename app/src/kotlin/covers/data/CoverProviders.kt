package org.rankoffline.app

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** The redirect allowlist belongs to the source that produced this exact URL. */
internal data class CoverImageCandidate(val uri: URI, val allowedHosts: Set<String>)

/** Only resolves candidate URLs. Actual networking, image validation and caching belong elsewhere. */
fun interface CoverProvider {
    fun resolve(item: MediaItem): URI?

    fun resolveCandidates(item: MediaItem): List<URI> = listOfNotNull(resolve(item))
}

/**
 * Resolves item and relation covers using ONLY sources declared by the containing .pack.
 * No providers, image hosts or content categories are built into the APK.
 * The manifest lookup must return a validated manifest for this exact PackId.
 */
class DeclarativePackCoverProvider(
    private val manifestProvider: (PackId) -> PackManifest?
) : CoverProvider {
    internal fun metadataCandidates(item: MediaItem): List<CoverMetadataCandidate> =
        metadataCandidates(item.packId, item.provider, item.remoteCoverRef)

    internal fun metadataCandidates(group: MediaGroup): List<CoverMetadataCandidate> =
        metadataCandidates(group.packId, group.provider, group.remoteCoverRef)

    override fun resolve(item: MediaItem): URI? = resolveCandidates(item).firstOrNull()

    override fun resolveCandidates(item: MediaItem): List<URI> =
        resolveImageCandidates(item).map { it.uri }

    internal fun resolveImageCandidates(item: MediaItem): List<CoverImageCandidate> = resolveCandidates(
        packId = item.packId,
        provider = item.provider,
        providerId = item.providerId,
        reference = item.remoteCoverRef
    )

    /** A group uses exactly the same declaration rules as an ordinary item. */
    internal fun resolveImageCandidates(group: MediaGroup): List<CoverImageCandidate> = resolveCandidates(
        packId = group.packId,
        provider = group.provider,
        providerId = group.providerGroupId,
        reference = group.remoteCoverRef
    )

    private fun resolveCandidates(
        packId: PackId,
        provider: String?,
        providerId: String?,
        reference: String?
    ): List<CoverImageCandidate> {
        val manifest = manifestProvider(packId)?.takeIf { it.packId == packId && it.capabilities.covers }
            ?: return emptyList()
        val ref = reference?.takeIf { it.isNotBlank() && it.length <= MAX_REFERENCE_LENGTH }
            ?: return emptyList()
        if (ref.startsWith("covers/")) return emptyList() // A bundled cover is a local resource.
        if (ref.startsWith("cover:v1|")) return emptyList() // Metadata references are resolved separately.

        if (ref.startsWith("{")) {
            // A malformed structured reference must not fall back to being a template parameter.
            val explicit = runCatching {
                coverReferenceJson.decodeFromString<DeclarativeCoverReference>(ref)
            }.getOrNull() ?: return emptyList()
            if (explicit.schemaVersion != 1 || explicit.identity.isBlank() ||
                explicit.identity.length > MAX_IDENTITY_LENGTH ||
                explicit.urls.size > MAX_FALLBACK_URLS
            ) return emptyList()
            val source = manifest.coverSources.firstOrNull { it.id == explicit.sourceId }
                ?: return emptyList()
            return if (explicit.urls.isNotEmpty()) {
                // Explicit URLs are constrained to this source's approved hosts.
                explicit.urls.map { raw ->
                    if (raw.length > MAX_URL_LENGTH) return emptyList()
                    val uri = runCatching { URI.create(raw) }.getOrNull() ?: return emptyList()
                    if (!isSafeHttpsUri(uri, source.allowedHosts)) return emptyList()
                    CoverImageCandidate(uri, source.allowedHosts)
                }.distinct()
            } else {
                fromTemplates(source, explicit.identity, providerId)
            }
        }

        // Plain references require a UNIQUE source for this provider. Otherwise
        // the record must explicitly select a coverSources.id with structured JSON.
        val sources = manifest.coverSources.filter {
            it.providerId == provider && it.metadataRequest == null
        }
        if (sources.size != 1) return emptyList()
        return fromTemplates(sources.single(), ref, providerId)
    }

    private fun metadataCandidates(
        packId: PackId,
        provider: String?,
        reference: String?
    ): List<CoverMetadataCandidate> {
        val manifest = manifestProvider(packId)?.takeIf { it.packId == packId && it.capabilities.covers }
            ?: return emptyList()
        val values = parseMetadataReference(reference) ?: return emptyList()
        return manifest.coverSources.mapNotNull { source ->
            val request = source.metadataRequest ?: return@mapNotNull null
            val identity = values[request.referenceKey] ?: return@mapNotNull null
            source.takeIf { it.providerId == provider }?.let { CoverMetadataCandidate(it, identity) }
        }
    }

    private fun fromTemplates(
        source: PackCoverSource,
        reference: String,
        providerId: String?
    ): List<CoverImageCandidate> {
        val encodedReference = encodeUrlComponent(reference) ?: return emptyList()
        return source.urlTemplates.mapNotNull { template ->
            val encodedProviderId = if ("{providerId}" in template) {
                encodeUrlComponent(providerId) ?: return@mapNotNull null
            } else ""
            val resolved = template.replace("{reference}", encodedReference)
                .replace("{providerId}", encodedProviderId)
            if (resolved.length > MAX_URL_LENGTH) return@mapNotNull null
            runCatching { URI.create(resolved) }.getOrNull()
                ?.takeIf { isSafeHttpsUri(it, source.allowedHosts) }
                ?.let { CoverImageCandidate(it, source.allowedHosts) }
        }.distinct()
    }

    private companion object {
        const val MAX_REFERENCE_LENGTH = 2_048
        const val MAX_IDENTITY_LENGTH = 512
        const val MAX_FALLBACK_URLS = 8
        const val MAX_URL_LENGTH = 4_096
    }
}

/** Versioned catalog-data syntax; no provider names or executable logic. */
@Serializable
private data class DeclarativeCoverReference(
    val schemaVersion: Int,
    val sourceId: String,
    val identity: String,
    val urls: List<String> = emptyList()
)

private val coverReferenceJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
    allowSpecialFloatingPointValues = false
}
