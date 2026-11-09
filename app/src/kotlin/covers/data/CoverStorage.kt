package org.rankoffline.app

import java.security.MessageDigest

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One physical cover root per pack. */
internal class CoverStorage(context: Context) {
    private val root = context.applicationContext.filesDir.toPath().toAbsolutePath().normalize()
        .resolve("packs")

    fun coversRoot(packId: PackId): File = checked(root.resolve(packId.directoryName()).resolve("covers")).toFile()

    /** Remote images are stored by entry identity; groups never collide with items. */
    fun coverFile(key: CatalogEntryKey): File {
        val kind = when (key.kind) {
            CatalogEntryKind.ITEM -> "items"
            CatalogEntryKind.GROUP -> "groups"
        }
        return checked(root.resolve(key.packId.directoryName()).resolve("covers")
            .resolve(kind).resolve(mediaCoverFileName(key.id))).toFile()
    }

    /** A pack may declare any safe cover path, including an exclusive group image. */
    fun declaredFile(packId: PackId, path: String): File? {
        if (!path.startsWith("covers/")) return null
        if (runCatching { PackManifestParser.validateDataPath(path) }.isFailure) return null
        return runCatching { checked(root.resolve(packId.directoryName()).resolve(path)).toFile() }.getOrNull()
    }

    fun owns(packId: PackId, file: File): Boolean {
        val candidate = file.toPath().toAbsolutePath().normalize()
        val covers = root.resolve(packId.directoryName()).resolve("covers")
        return candidate != covers && candidate.startsWith(covers) &&
            !hasSymlink(candidate) && !Files.isDirectory(candidate)
    }

    fun owns(key: CatalogEntryKey, file: File): Boolean =
        owns(key.packId, file) && (file.toPath().toAbsolutePath().normalize() ==
            coverFile(key).toPath().toAbsolutePath().normalize())

    private fun checked(path: Path): Path {
        require(path.startsWith(root) && !hasSymlink(path)) { "Unsafe cover path" }
        return path
    }

    private fun hasSymlink(path: Path): Boolean {
        if (!path.startsWith(root)) return true
        var cursor: Path? = path
        // Also validate the packs root itself, including when it is a symlink.
        while (cursor != null && cursor.startsWith(root)) {
            if (Files.isSymbolicLink(cursor)) return true
            cursor = cursor.parent
        }
        return false
    }
}

/** Prevents a removal or installation from racing a cover write for the same pack. */
internal object PackCoverWriteLocks {
    private val locks = ConcurrentHashMap<PackId, Mutex>()
    suspend fun <T> withPackLock(packId: PackId, block: suspend () -> T): T =
        locks.computeIfAbsent(packId) { Mutex() }.withLock { block() }
}

/** Stable filename *inside an already pack-scoped and entry-kind-scoped cache directory*. */
internal fun mediaCoverFileName(entryId: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(entryId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + ".cover"
