package org.rankoffline.app

import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Metadata from a TRUSTED registry, not an arbitrary URL discovered inside a pack. */
data class PackDownloadDescriptor(
    val manifest: PackManifest,
    val archiveUrl: URI,
    val archiveBytes: Long,
    val archiveSha256: String,
    val registryId: String
) {
    val packId: PackId get() = manifest.packId
    init {
        require(archiveUrl.scheme.equals("https", ignoreCase = true) &&
            !archiveUrl.host.isNullOrBlank() && archiveUrl.userInfo == null &&
            archiveUrl.fragment == null && archiveUrl.port in listOf(-1, 443))
        require(archiveBytes > 0 && archiveBytes <= MAX_ARCHIVE_BYTES)
        require(archiveSha256.matches(Regex("[a-f0-9]{64}")))
        require(registryId.isNotBlank() && registryId.length <= 128)
    }
    companion object { const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024 }
}

/** No built-in list of official packs; replace this map from GitHub or any future registry. */
class PackSourceRegistry {
    private val _descriptors = MutableStateFlow<Map<PackId, PackDownloadDescriptor>>(emptyMap())
    val descriptors: StateFlow<Map<PackId, PackDownloadDescriptor>> = _descriptors.asStateFlow()

    fun replace(descriptors: Collection<PackDownloadDescriptor>) {
        require(descriptors.map { it.packId }.distinct().size == descriptors.size) {
            "Duplicate pack identity in registry"
        }
        _descriptors.value = descriptors.associateBy { it.packId }
    }

    fun descriptor(packId: PackId): PackDownloadDescriptor? = _descriptors.value[packId]
}
