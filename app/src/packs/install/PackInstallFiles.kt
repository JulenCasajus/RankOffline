package org.rankoffline.app

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** File operations deliberately do NOT follow symbolic links, including during deletion. */
internal fun File.isSymbolicLink(): Boolean = Files.isSymbolicLink(toPath())

internal fun safePayloadFile(base: File, relative: String): File {
    PackManifestParser.validateDataPath(relative)
    val root = base.canonicalFile
    val target = File(root, relative).canonicalFile
    require(target.path.startsWith(root.path + File.separator)) { "Payload escapes pack root" }
    require(!File(base, relative).isSymbolicLink()) { "Payload symlinks are forbidden" }
    return target
}

internal fun deleteOwnedTree(file: File): Boolean = runCatching {
    val path = file.toPath()
    if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return@runCatching true
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
    Files.notExists(path, LinkOption.NOFOLLOW_LINKS)
}.getOrDefault(false)

internal fun deleteMarker(file: File): Boolean =
    Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS) || runCatching {
        Files.delete(file.toPath())
        true
    }.getOrDefault(false)

internal fun ownedTreeBytes(file: File): Long {
    val path = file.toPath()
    if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return 0
    var total = 0L
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            total = Math.addExact(total, attrs.size())
            return FileVisitResult.CONTINUE
        }
    })
    return total
}

/** Cross-instance and cross-process lock for mutations of a shared pack root. */
private val inProcessInstallLock = Any()

internal fun <T> File.withInstallLock(action: () -> T): T = synchronized(inProcessInstallLock) {
    check(isDirectory || mkdirs()) { "Cannot create pack directory" }
    check(!isSymbolicLink()) { "Pack root must not be a symlink" }
    FileChannel.open(File(this, ".pack-install.lock").toPath(),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        channel.lock().use { action() }
    }
}

internal fun readInstalledManifest(directory: File): PackManifest? = runCatching {
    if (directory.isSymbolicLink() || !directory.isDirectory) return@runCatching null
    val manifestFile = File(directory, PackInstaller.PACK_MANIFEST)
    if (manifestFile.isSymbolicLink() || !manifestFile.isFile ||
        manifestFile.length() !in 1..PackManifestParser.MAX_MANIFEST_BYTES
    ) return@runCatching null
    PackManifestParser.parse(manifestFile.readText(Charsets.UTF_8))
}.getOrNull()

internal fun isComplete(directory: File, packId: PackId): Boolean {
    if (directory.isSymbolicLink() || !directory.isDirectory) return false
    val complete = File(directory, PackInstaller.COMPLETE_MARKER)
    if (complete.isSymbolicLink() || !complete.isFile) return false
    return readInstalledManifest(directory)?.packId == packId
}
