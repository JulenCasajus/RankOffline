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
        check(root.isDirectory || root.mkdirs()) { "Unable to create category-pack root" }
        check(!deletionMarker(manifest.packId).exists()) {
            "Pack deletion must finish before installation"
        }
        // A retained .previous means an earlier replacement was promoted but never committed by
        // the Room/runtime phase. Reconcile that checkpoint before attempting another install so
        // repeated invocations cannot delete the only known-good installation.
        check(recover(manifest.packId)) { "Unable to recover interrupted category-pack operation" }
        val destination = File(root, manifest.packId.value)
        val staging = File(root, ".${manifest.packId.value}-${UUID.randomUUID()}.part")
        check(staging.mkdirs()) { "Unable to create category-pack staging directory" }
        try {
            manifest.files.forEach { spec ->
                val output = File(staging, spec.name)
                require(output.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                output.parentFile?.let { parent ->
                    check(parent.isDirectory || parent.mkdirs()) {
                        "Unable to create category-pack directory"
                    }
                }
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
            check(deleteRecursivelyIfPresent(previous)) { "Unable to remove retained pack" }
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
                if (!retainPreviousUntilCommit) {
                    check(deleteRecursivelyIfPresent(previous)) { "Unable to commit category pack" }
                }
            } catch (failure: Exception) {
                if (!destination.exists() && previous.exists()) {
                    Files.move(previous.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                throw failure
            }
            return destination
        } finally {
            deleteRecursivelyIfPresent(staging)
        }
    }

    fun installedDirectory(packId: PackId): File? =
        File(root, packId.value).takeIf {
            File(it, COMPLETE_MARKER).isFile && File(it, PACK_MANIFEST).isFile
        }

    fun installedDirectory(mediaType: MediaType): File? = installedDirectory(mediaType.toPackId())

    fun packDirectory(packId: PackId): File = File(root, packId.value)

    fun residualBytes(packId: PackId): Long = ownedArtifacts(packId)
        .filter(File::exists)
        .sumOf(File::recursiveBytes)

    fun hasPendingDeletion(packId: PackId): Boolean = deletionMarker(packId).exists()

    fun matchesInstalledManifest(manifest: CategoryPackInstallManifest): Boolean {
        val directory = installedDirectory(manifest.packId) ?: return false
        if (hasPendingDeletion(manifest.packId)) return false
        val encoded = runCatching { File(directory, PACK_MANIFEST).readText(Charsets.UTF_8) }
            .getOrNull() ?: return false
        if (encoded != manifest.encodedManifest) return false
        // Bundled covers become user-removable cache entries after installation. Their absence
        // must not invalidate an otherwise verified catalog on every later startup.
        return manifest.files.filterNot { it.name.startsWith("covers/") }.all { spec ->
            val file = File(directory, spec.name)
            runCatching {
                file.canonicalPath.startsWith(directory.canonicalPath + File.separator) &&
                    file.isFile && file.length() == spec.bytes
            }.getOrDefault(false)
        }
    }

    /** Commits a promoted replacement only after its Room catalog and groups are ready. */
    fun commitReplacement(packId: PackId) {
        check(deleteRecursivelyIfPresent(File(root, ".${packId.value}.previous"))) {
            "Unable to remove retained category pack"
        }
    }

    /** Restores the last complete pack, or removes a failed first installation. */
    fun rollbackReplacement(packId: PackId) {
        val destination = File(root, packId.value)
        val previous = File(root, ".${packId.value}.previous")
        check(deleteRecursivelyIfPresent(destination)) { "Unable to remove failed category pack" }
        if (previous.exists()) {
            Files.move(previous.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Repairs an interrupted atomic promotion and removes inactive staging directories. */
    fun recover(packId: PackId): Boolean {
        check(root.isDirectory || root.mkdirs()) { "Unable to create category-pack root" }
        val destination = File(root, packId.value)
        val previous = File(root, ".${packId.value}.previous")
        if (hasPendingDeletion(packId)) {
            val deleted = installationArtifacts(packId)
                .map(::deleteRecursivelyIfPresent)
                .all { it }
            if (deleted && !deletionMarker(packId).delete() && deletionMarker(packId).exists()) {
                return false
            }
            return deleted
        }
        if (File(previous, COMPLETE_MARKER).isFile) {
            // A retained previous directory means promotion did not reach runtime READY. Prefer
            // the last known-good files; startup will reconcile Room metadata with them.
            check(deleteRecursivelyIfPresent(destination)) {
                "Unable to remove interrupted replacement"
            }
            Files.move(previous.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return root.listFiles().orEmpty()
            .filter { it.name.startsWith(".${packId.value}-") && it.name.endsWith(".part") }
            .map(::deleteRecursivelyIfPresent)
            .all { it }
    }

    fun recover(mediaType: MediaType): Boolean = recover(mediaType.toPackId())

    fun deleteInstalledPack(packId: PackId): Boolean {
        // Cleanup also owns retained replacements and staging left by interruption. Restricting
        // the targets to this exact PackId avoids touching unrelated installations.
        check(root.isDirectory || root.mkdirs()) { "Unable to create category-pack root" }
        val marker = deletionMarker(packId)
        if (!marker.exists()) marker.writeText("deleting", Charsets.UTF_8)
        val deleted = installationArtifacts(packId)
            .map(::deleteRecursivelyIfPresent)
            .all { it }
        val markerDeleted = !deleted || marker.delete() || !marker.exists()
        return deleted && markerDeleted
    }

    fun deleteInstalledPack(mediaType: MediaType): Boolean = deleteInstalledPack(mediaType.toPackId())

    private fun installationArtifacts(packId: PackId): List<File> = buildList {
        add(packDirectory(packId))
        add(File(root, ".${packId.value}.previous"))
        addAll(
            root.listFiles().orEmpty().filter {
                it.name.startsWith(".${packId.value}-") && it.name.endsWith(".part")
            }
        )
    }.distinctBy { it.absolutePath }

    private fun ownedArtifacts(packId: PackId): List<File> =
        installationArtifacts(packId) + deletionMarker(packId)

    private fun deletionMarker(packId: PackId): File = File(root, ".${packId.value}.deleting")

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

private fun File.recursiveBytes(): Long = when {
    isFile -> length()
    isDirectory -> listFiles().orEmpty().sumOf(File::recursiveBytes)
    else -> 0L
}

private fun deleteRecursivelyIfPresent(file: File): Boolean =
    !file.exists() || (file.deleteRecursively() && !file.exists())

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
