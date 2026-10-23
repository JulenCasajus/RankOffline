package org.rankoffline.app

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class CategoryPackFileSpec(
    val name: String,
    val bytes: Long,
    val sha256: String
)

data class CategoryPackInstallManifest(
    val packId: PackId,
    val snapshot: String,
    val downloadBytes: Long,
    val installedBytes: Long,
    val files: List<CategoryPackFileSpec>,
    val encodedManifest: String
) {
    val mediaType: MediaType?
        get() = packId.toOfficialMediaTypeOrNull()
}

/** Atomic, provider-agnostic local installer used once signed pack hosting is configured. */
class CategoryPackInstaller(private val root: File) {
    fun install(
        manifest: CategoryPackInstallManifest,
        retainPreviousUntilCommit: Boolean = false,
        openFile: (CategoryPackFileSpec) -> InputStream
    ): File {
        validateManifest(manifest)
        root.mkdirs()
        val destination = File(root, manifest.packId.value)
        val staging = File(root, ".${manifest.packId.value}-${UUID.randomUUID()}.part")
        check(staging.mkdirs()) { "Unable to create category-pack staging directory" }
        try {
            manifest.files.forEach { spec ->
                val output = File(staging, spec.name)
                require(output.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                output.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                openFile(spec).use { input ->
                    output.outputStream().buffered().use { target ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            bytes += read
                            require(bytes <= spec.bytes) { "Pack file exceeds declared byte length" }
                            digest.update(buffer, 0, read)
                            target.write(buffer, 0, read)
                        }
                    }
                }
                require(bytes == spec.bytes) { "Pack file byte length mismatch" }
                require(digest.hex() == spec.sha256.lowercase()) { "Pack file SHA-256 mismatch" }
            }
            File(staging, PACK_MANIFEST).writeText(manifest.encodedManifest, Charsets.UTF_8)
            File(staging, COMPLETE_MARKER).writeText(manifest.snapshot, Charsets.UTF_8)

            val previous = File(root, ".${manifest.packId.value}.previous")
            previous.deleteRecursively()
            if (destination.exists()) {
                Files.move(destination.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(
                    staging.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                if (!retainPreviousUntilCommit) previous.deleteRecursively()
            } catch (failure: Exception) {
                if (!destination.exists() && previous.exists()) {
                    Files.move(previous.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                throw failure
            }
            return destination
        } finally {
            staging.deleteRecursively()
        }
    }

    fun installedDirectory(packId: PackId): File? =
        File(root, packId.value).takeIf {
            File(it, COMPLETE_MARKER).isFile && File(it, PACK_MANIFEST).isFile
        }

    fun installedDirectory(mediaType: MediaType): File? = installedDirectory(mediaType.toPackId())

    /** Commits a promoted replacement only after its Room catalog and groups are ready. */
    fun commitReplacement(packId: PackId) {
        File(root, ".${packId.value}.previous").deleteRecursively()
    }

    /** Restores the last complete pack, or removes a failed first installation. */
    fun rollbackReplacement(packId: PackId) {
        val destination = File(root, packId.value)
        val previous = File(root, ".${packId.value}.previous")
        destination.deleteRecursively()
        if (previous.exists()) {
            Files.move(previous.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Repairs an interrupted atomic promotion and removes inactive staging directories. */
    fun recover(packId: PackId) {
        root.mkdirs()
        val destination = File(root, packId.value)
        val previous = File(root, ".${packId.value}.previous")
        if (File(previous, COMPLETE_MARKER).isFile) {
            // A retained previous directory means promotion did not reach runtime READY. Prefer
            // the last known-good files; startup will reconcile Room metadata with them.
            destination.deleteRecursively()
            Files.move(previous.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        root.listFiles().orEmpty()
            .filter { it.name.startsWith(".${packId.value}-") && it.name.endsWith(".part") }
            .forEach(File::deleteRecursively)
    }

    fun recover(mediaType: MediaType) = recover(mediaType.toPackId())

    fun deleteInstalledPack(packId: PackId): Boolean {
        val directory = installedDirectory(packId) ?: return true
        return directory.deleteRecursively()
    }

    fun deleteInstalledPack(mediaType: MediaType): Boolean = deleteInstalledPack(mediaType.toPackId())

    private fun validateManifest(manifest: CategoryPackInstallManifest) {
        require(manifest.snapshot.isNotBlank())
        require(manifest.downloadBytes >= 0L && manifest.installedBytes >= 0L)
        require(manifest.files.isNotEmpty())
        require(manifest.files.sumOf(CategoryPackFileSpec::bytes) == manifest.installedBytes) {
            "Pack installed byte total mismatch"
        }
        require(manifest.files.map(CategoryPackFileSpec::name).distinct().size == manifest.files.size)
        manifest.files.forEach { spec ->
            PackManifestParser.validateDataPath(spec.name)
            require(spec.bytes >= 0L)
            require(spec.sha256.matches(SHA_256))
        }
    }

    companion object {
        const val COMPLETE_MARKER = ".complete"
        const val PACK_MANIFEST = "manifest.json"
        private val SHA_256 = Regex("[0-9a-fA-F]{64}")
    }
}

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
