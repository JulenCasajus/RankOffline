package org.rankoffline.app

import java.util.Locale
import org.json.JSONObject

internal data class PackIntegrityData(
    val files: List<PackManifestFile>,
    val declared: Map<String, PackManifestFile>
)

internal data class PackPayloadSections(
    val catalog: List<String>,
    val groups: List<String>,
    val covers: List<String>
)

/** Strict declared-file inventory and payload section cross-references. */
internal object PackManifestIntegrity {
    private const val MAX_PAYLOAD_FILES = 100_000
    private val SHA_256 = Regex("[0-9a-fA-F]{64}")

    fun parse(root: JSONObject): PackIntegrityData {
        root.requireOnlyKeys("installedBytes", "files")
        val fileEntries = root.getJSONArray("files")
        require(fileEntries.length() in 1..MAX_PAYLOAD_FILES)
        val files = fileEntries.objects().map { entry ->
            entry.requireOnlyKeys("path", "bytes", "sha256")
            PackManifestFile(
                path = entry.getString("path").also(PackManifestJson::validateDataPath),
                bytes = entry.getLong("bytes").also { require(it >= 0L) },
                sha256 = entry.getString("sha256").also {
                    require(it.matches(SHA_256)) { "Invalid SHA-256" }
                }.lowercase(Locale.ROOT)
            )
        }
        require(files.map { it.path }.distinct().size == files.size) { "Duplicate payload paths" }
        require(files.map { it.path.lowercase(Locale.ROOT) }.distinct().size == files.size) {
            "Payload paths collide on a case-insensitive filesystem"
        }
        val totalBytes = files.fold(0L) { total, file -> Math.addExact(total, file.bytes) }
        require(totalBytes == root.getLong("installedBytes")) { "Installed byte total mismatch" }
        return PackIntegrityData(files, files.associateBy { it.path })
    }

    fun sections(
        root: JSONObject,
        declared: Map<String, PackManifestFile>,
        merge: PackMergeDefinition,
        capabilities: PackCapabilities
    ): PackPayloadSections {
        fun payloadList(section: String): List<String> = root.getJSONArray(section).strings().also { paths ->
            require(paths.distinct().size == paths.size) { "Duplicate $section payload" }
            paths.forEach { path ->
                PackManifestJson.validateDataPath(path)
                require(path.startsWith("$section/") && path in declared) {
                    "Undeclared $section payload: $path"
                }
            }
        }
        val catalog = payloadList("catalog")
        val groups = payloadList("groups")
        val covers = payloadList("covers")
        require(catalog.isNotEmpty()) { "A pack must contain catalog data" }
        require(!merge.enabled || groups.isNotEmpty()) { "Enabled merge requires group data" }
        require(capabilities.covers || covers.isEmpty()) { "Local covers declared with covers disabled" }
        return PackPayloadSections(catalog, groups, covers)
    }
}
