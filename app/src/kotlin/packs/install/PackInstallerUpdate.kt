package org.rankoffline.app

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID

/** The old directory remains intact until a verified replacement has been staged. */
internal fun PackInstaller.stageUpdate(
    request: PackInstallRequest, openFile: (PackManifestFile) -> InputStream
): File = root.withInstallLock {
    val manifest = PackManifestParser.parse(request.manifestJson)
    require(request.sourceRevision == null ||
        (request.sourceRevision.length in 1..160 && request.sourceRevision.none(Char::isISOControl))) {
        "Invalid revision"
    }
    val id = manifest.packId
    val destination = packDirectory(id)
    check(isComplete(destination, id) && !hasPendingInstallation(id) &&
        !hasPendingDeletion(id) && !hasPendingUpdate(id) && !previousDirectory(id).exists()) {
        "Update requires a complete, recoverable installation"
    }
    val oldSlug = requireNotNull(readInstalledManifest(destination)).slug
    require(oldSlug == null || oldSlug == manifest.slug) { "Pack slug cannot change during update" }
    require(manifest.files.isNotEmpty() && manifest.files.none {
        PackInstaller.GENERATED_COVER_PATH.matches(it.path)
    }) { "Invalid update payload" }
    require(manifest.files.fold(0L) { sum, spec -> Math.addExact(sum, spec.bytes) } <= maxInstalledBytes) {
        "Update exceeds size limit"
    }
    val staging = File(root, ".${id.directoryName()}-${UUID.randomUUID()}.part")
    check(staging.mkdir()) { "Cannot stage update" }
    var promoted = false
    try {
        manifest.files.forEach { spec ->
            val output = safePayloadFile(staging, spec.path)
            check(output.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            writeVerifiedPayload(openFile(spec), output, spec)
        }
        copyExistingCovers(destination, staging, maxInstalledBytes)
        writeSynced(File(staging, PackInstaller.PACK_MANIFEST), request.manifestJson)
        writeSynced(File(staging, PackInstaller.COMPLETE_MARKER), request.sourceRevision.orEmpty())
        writeSynced(updateFile(id), "updating")
        moveAtomic(destination, previousDirectory(id))
        moveAtomic(staging, destination)
        promoted = true
        destination
    } finally {
        if (!promoted) {
            // Also handles failure between the two atomic directory moves.
            val previous = previousDirectory(id)
            if (previous.exists()) {
                check(deleteOwnedTree(destination))
                moveAtomic(previous, destination)
            }
            check(deleteOwnedTree(staging))
            check(deleteMarker(updateFile(id)))
        }
    }
}

/** Room has committed the new catalog; the backup is no longer needed. */
internal fun PackInstaller.commitUpdate(id: PackId) = root.withInstallLock {
    check(hasPendingUpdate(id) && isComplete(packDirectory(id), id)) { "No complete update" }
    check(deleteOwnedTree(previousDirectory(id))) { "Cannot remove update backup" }
    check(deleteMarker(updateFile(id))) { "Cannot finalize update" }
}

/** Generated cover paths depend on entry identity; copied files keep their exact paths. */
private fun copyExistingCovers(old: File, staging: File, maxBytes: Long) {
    var copiedBytes = 0L
    for (kind in listOf("items", "groups")) {
        val source = File(old, "covers/$kind")
        if (!source.isDirectory || source.isSymbolicLink()) continue
        val target = File(staging, "covers/$kind")
        for (cover in source.listFiles().orEmpty()) {
            val path = "covers/$kind/${cover.name}"
            if (!PackInstaller.GENERATED_COVER_PATH.matches(path) || cover.isSymbolicLink() ||
                !cover.isFile || cover.length() !in 1..CoverImageFiles.MAX_BYTES) continue
            copiedBytes = Math.addExact(copiedBytes, cover.length())
            require(copiedBytes <= maxBytes) { "Stored covers exceed update copy limit" }
            check(target.isDirectory || target.mkdirs()) { "Cannot preserve covers" }
            val output = File(target, cover.name)
            check(!Files.exists(output.toPath())) { "Update cover conflicts with payload" }
            cover.inputStream().use { input ->
                FileOutputStream(output).use { stream ->
                    input.copyTo(stream)
                    stream.fd.sync()
                }
            }
        }
    }
}
