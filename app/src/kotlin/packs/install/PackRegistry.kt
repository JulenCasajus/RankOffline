package org.rankoffline.app

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PackDefinition(
    val manifest: PackManifest,
    val installed: Boolean,
    val downloadBytes: Long?
) {
    val packId: PackId get() = manifest.packId
}

/** Discovers completed installations. Archive verification belongs to the installer. */
class PackRegistry(private val root: File) {
    private val cached = mutableMapOf<PackId, Pair<List<Long>, PackManifest>>()
    private val _changedPacks = MutableStateFlow<Set<PackId>>(emptySet())
    val changedPacks: StateFlow<Set<PackId>> = _changedPacks.asStateFlow()
    private val _definitions = MutableStateFlow<Map<PackId, PackDefinition>>(emptyMap())
    val definitions: StateFlow<Map<PackId, PackDefinition>> = _definitions.asStateFlow()

    fun refresh(downloadBytes: Map<PackId, Long?> = emptyMap()): Map<PackId, PackDefinition> {
        val changed = mutableSetOf<PackId>()
        val previous = _definitions.value.keys
        val discovered = root.listFiles().orEmpty().asSequence()
            .filter { it.isDirectory && !it.isSymbolicLink() && PackId.fromDirectoryName(it.name) != null }
            .mapNotNull { directory ->
                val packId = PackId.fromDirectoryName(directory.name) ?: return@mapNotNull null
                if (File(root, ".${packId.directoryName()}.pending").exists() ||
                    File(root, ".${packId.directoryName()}.deleting").exists()
                ) return@mapNotNull null
                val marker = File(directory, PackInstaller.COMPLETE_MARKER)
                if (!marker.isFile || marker.isSymbolicLink()) return@mapNotNull null
                val manifestFile = File(directory, PackInstaller.PACK_MANIFEST)
                if (!manifestFile.isFile || manifestFile.isSymbolicLink() ||
                    manifestFile.length() !in 1..PackManifestParser.MAX_MANIFEST_BYTES)
                    return@mapNotNull null
                val stamp = listOf(manifestFile.length(), manifestFile.lastModified(),
                    marker.lastModified(), File(directory, "catalog").lastModified(),
                    File(directory, "groups").lastModified())
                if (cached[packId] != null && cached[packId]?.first != stamp) changed += packId
                val manifest = cached[packId]?.takeIf { it.first == stamp }?.second
                    ?: readInstalledManifest(directory)?.also { cached[packId] = stamp to it }
                    ?: return@mapNotNull null
                if (manifest.packId != packId) return@mapNotNull null
                manifest.packId to PackDefinition(
                    manifest = manifest,
                    installed = true,
                    downloadBytes = downloadBytes[manifest.packId]
                )
            }.toMap()
        cached.keys.retainAll(discovered.keys)
        _changedPacks.value = changed + (previous - discovered.keys) + (discovered.keys - previous)
        _definitions.value = discovered
        return discovered
    }

    fun definition(packId: PackId): PackDefinition? = _definitions.value[packId]
}
