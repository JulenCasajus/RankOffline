package org.rankoffline.app

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Optional verified ZIP cache for offline recovery. Not required for normal installed use. */
internal class PackArchiveStore(private val root: File) {
    fun retained(packId: PackId): File? = File(root, "${packId.value}.pack")
        .takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }

    fun bytes(packId: PackId): Long = retained(packId)?.length() ?: 0L

    /** Never make a failed cache write invalidate an otherwise committed installation. */
    fun retain(packId: PackId, verifiedArchive: File, expected: PackDownloadDescriptor) {
        require(packId == expected.packId)
        require(verifiedArchive.length() == expected.archiveBytes)
        check(root.isDirectory || root.mkdirs())
        check(!Files.isSymbolicLink(root.toPath()))
        val temporary = File(root, ".${packId.value}-${UUID.randomUUID()}.tmp")
        try {
            verifiedArchive.inputStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.length() == expected.archiveBytes)
            val hash = MessageDigest.getInstance("SHA-256")
            temporary.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) hash.update(buffer, 0, n)
                }
            }
            check(hash.digest().joinToString("") { "%02x".format(it) } == expected.archiveSha256)
            try {
                Files.move(temporary.toPath(), File(root, "${packId.value}.pack").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), File(root, "${packId.value}.pack").toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { deleteOwnedTree(temporary) }
    }

    fun delete(packId: PackId): Boolean = deleteOwnedTree(File(root, "${packId.value}.pack"))
}
