package org.rankoffline.app

import java.io.File
import java.io.InputStream
import java.util.UUID

/** The manifest is the authority for the initially installed data-only payload. */
data class PackInstallRequest(val manifestJson: String, val sourceRevision: String? = null)

enum class PackRecoveryState { CLEAN, AWAITING_COMMIT }

/** Installs a pack once. Updating/replacing an already installed pack is not supported. */
class PackInstaller(
    internal val root: File,
    internal val maxInstalledBytes: Long = 2L * 1024 * 1024 * 1024
) {
    init { require(maxInstalledBytes > 0L) }

    /** Verify the entire payload before making the new pack visible. */
    fun install(request: PackInstallRequest, openFile: (PackManifestFile) -> InputStream): File =
        root.withInstallLock {
            val manifest = PackManifestParser.parse(request.manifestJson)
            require(request.sourceRevision == null ||
                (request.sourceRevision.length in 1..160 &&
                 request.sourceRevision.none(Char::isISOControl))) { "Invalid revision" }
            require(manifest.files.isNotEmpty()) { "Empty pack" }
            // The importer may choose any other covers/ filename. Reserve generated entry paths.
            require(manifest.files.none { GENERATED_COVER_PATH.matches(it.path) }) {
                "Pack cover path conflicts with generated entry cover names"
            }
            val payloadSize = manifest.files.fold(0L) { sum, spec -> Math.addExact(sum, spec.bytes) }
            require(payloadSize <= maxInstalledBytes) { "Pack exceeds size limit" }
            val packId = manifest.packId
            check(!hasPendingDeletion(packId) && !hasPendingInstallation(packId)) {
                "Pack operation requires recovery"
            }
            val destination = packDirectory(packId)
            check(!destination.exists() && !destination.isSymbolicLink()) {
                "Pack is already installed; updates are not supported"
            }
            val staging = File(root, ".${packId.directoryName()}-${UUID.randomUUID()}.part")
            check(staging.mkdir()) { "Cannot create pack staging directory" }
            var promoted = false
            try {
                for (spec in manifest.files) {
                    val output = safePayloadFile(staging, spec.path)
                    val parent = requireNotNull(output.parentFile) {
                        "Payload has no parent directory"
                    }
                    check(parent.isDirectory || parent.mkdirs()) {
                        "Cannot create payload directory"
                    }
                    writeVerifiedPayload(openFile(spec), output, spec)
                }
                writeSynced(File(staging, PACK_MANIFEST), request.manifestJson)
                writeSynced(File(staging, COMPLETE_MARKER), request.sourceRevision.orEmpty())
                // The pending marker survives process death until Room has imported the pack.
                writeSynced(pendingFile(packId), "pending")
                moveAtomic(staging, destination)
                promoted = true
                destination
            } finally {
                if (!promoted) {
                    deleteOwnedTree(staging)
                    if (!destination.exists()) deleteMarker(pendingFile(packId))
                }
            }
        }

    /** Pending installations are visible to the importer, but not READY until committed. */
    fun installedDirectory(packId: PackId): File? =
        packDirectory(packId).takeIf { isComplete(it, packId) && !hasPendingDeletion(packId) }

    fun packDirectory(packId: PackId) = File(root, packId.directoryName())
    fun hasPendingInstallation(packId: PackId) = pendingFile(packId).exists()
    fun hasPendingDeletion(packId: PackId) = deletionFile(packId).exists()

    /** User-removable cover files are optional; they do not invalidate the pack installation. */
    fun matchesInstalledManifest(expected: PackManifest, verifyPayloadHashes: Boolean = false): Boolean {
        if (hasPendingInstallation(expected.packId) || hasPendingDeletion(expected.packId)) return false
        val directory = installedDirectory(expected.packId) ?: return false
        if (readInstalledManifest(directory) != expected) return false
        return expected.files.asSequence().filterNot { it.path.startsWith("covers/") }.all { spec ->
            runCatching {
                val file = safePayloadFile(directory, spec.path)
                !file.isSymbolicLink() && file.isFile && file.length() == spec.bytes &&
                    (!verifyPayloadHashes || sha256Of(file) == spec.sha256)
            }.getOrDefault(false)
        }
    }

    /** Called ONLY after the Room import succeeds. */
    fun commitInstallation(packId: PackId) = root.withInstallLock {
        check(isComplete(packDirectory(packId), packId)) { "Pack is incomplete" }
        check(deleteMarker(pendingFile(packId))) { "Unable to finalize installation" }
    }

    /** Called after a failed first import; ratings and notes are not touched here. */
    fun abortInstallation(packId: PackId) = root.withInstallLock {
        check(hasPendingInstallation(packId)) { "No pending installation" }
        check(deleteOwnedTree(packDirectory(packId))) { "Unable to discard failed installation" }
        check(deleteMarker(pendingFile(packId))) { "Unable to clear pending marker" }
    }

    /** A complete pending install must replay its idempotent Room import at startup. */
    fun recover(packId: PackId): PackRecoveryState = root.withInstallLock {
        if (hasPendingDeletion(packId)) {
            check(deleteOwnedTree(packDirectory(packId))) { "Deletion recovery failed" }
            check(deleteMarker(pendingFile(packId)) && deleteMarker(deletionFile(packId)))
        }
        check(stagingDirectories(packId).all(::deleteOwnedTree)) { "Staging recovery failed" }
        if (hasPendingInstallation(packId)) {
            if (isComplete(packDirectory(packId), packId)) PackRecoveryState.AWAITING_COMMIT
            else {
                check(deleteOwnedTree(packDirectory(packId)))
                check(deleteMarker(pendingFile(packId)))
                PackRecoveryState.CLEAN
            }
        } else PackRecoveryState.CLEAN
    }

    /** Removes files only, never personal ratings or preferences. */
    fun deleteInstalledPack(packId: PackId): Boolean = root.withInstallLock {
        if (!deletionFile(packId).exists()) writeSynced(deletionFile(packId), "deleting")
        deleteOwnedTree(packDirectory(packId)) &&
            stagingDirectories(packId).all(::deleteOwnedTree) &&
            deleteMarker(pendingFile(packId)) && deleteMarker(deletionFile(packId))
    }

    companion object {
        const val PACK_MANIFEST = "manifest.json"
        const val COMPLETE_MARKER = ".complete"
        internal val GENERATED_COVER_PATH = Regex("covers/(items|groups)/[0-9a-f]{64}\\.cover")
    }
}
