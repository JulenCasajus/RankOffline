package org.rankoffline.app

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONObject

enum class PackCoverAcquisitionStatus { PENDING, COMPLETED, PARTIAL, CANCELLED, SKIPPED }

data class PackCoverAcquisition(
    val status: PackCoverAcquisitionStatus,
    val target: Int = 0,
    val available: Int = 0,
    val unavailable: Int = 0,
    val errors: Int = 0
) {
    init { require(target >= 0 && available >= 0 && unavailable >= 0 && errors >= 0) }
}

/** Persists the optional image stage without tying it to installation or content kind. */
class PackCoverStatusStore(private val root: File) {
    private val lock = Any()
    fun read(packId: PackId): PackCoverAcquisition? = synchronized(lock) {
        runCatching {
            val file = statusFile(packId)
            if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() > 16_384) return@runCatching null
            val json = JSONObject(file.readText(Charsets.UTF_8))
            PackCoverAcquisition(
                status = PackCoverAcquisitionStatus.valueOf(json.getString("status")),
                target = json.getInt("target"),
                available = json.getInt("available"),
                unavailable = json.getInt("unavailable"),
                errors = json.getInt("errors")
            )
        }.getOrNull()
    }

    fun write(packId: PackId, result: PackCoverAcquisition) = synchronized(lock) {
        if (read(packId)?.status == PackCoverAcquisitionStatus.CANCELLED &&
            result.status != PackCoverAcquisitionStatus.CANCELLED) return@synchronized
        check(root.isDirectory || root.mkdirs())
        check(!Files.isSymbolicLink(root.toPath()))
        val temporary = File(root, ".${packId.value}-${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(JSONObject()
                .put("status", result.status.name).put("target", result.target)
                .put("available", result.available).put("unavailable", result.unavailable)
                .put("errors", result.errors).toString(), Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), statusFile(packId).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), statusFile(packId).toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { deleteOwnedTree(temporary) }
    }

    fun cancel(packId: PackId) = synchronized(lock) {
        val previous = read(packId) ?: return@synchronized
        if (previous.status == PackCoverAcquisitionStatus.PENDING) {
            write(packId, previous.copy(status = PackCoverAcquisitionStatus.CANCELLED))
        }
    }

    fun delete(packId: PackId): Boolean = synchronized(lock) { deleteOwnedTree(statusFile(packId)) }
    private fun statusFile(packId: PackId): File = File(root, "${packId.value}.json")
}
