package org.rankoffline.app

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest


internal fun writeVerifiedPayload(input: InputStream, output: File, spec: PackManifestFile) {
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    input.use { source ->
        FileOutputStream(output).use { target ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                require(read.toLong() <= spec.bytes - count) { "Oversized payload: ${spec.path}" }
                target.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                count += read
            }
            target.fd.sync()
        }
    }
    require(count == spec.bytes) { "Incorrect size: ${spec.path}" }
    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
    require(actualSha == spec.sha256) { "SHA-256 mismatch: ${spec.path}" }
}

internal fun writeSynced(file: File, value: String) {
    val bytes = ByteBuffer.wrap(value.toByteArray(Charsets.UTF_8))
    FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
        while (bytes.hasRemaining()) channel.write(bytes)
        channel.force(true)
    }
}

internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun moveAtomic(from: File, to: File) {
    check(!to.exists() && !to.isSymbolicLink()) { "Destination already exists: ${to.name}" }
    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
}
