package org.rankoffline.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Shared bounded reads and path checks for independent user-owned local files. */
internal fun rejectSymlink(file: File) {
    if (file.absoluteFile != file.canonicalFile) {
        throw IOException("Symlinks are not allowed in local data paths: ${file.name}")
    }
}

internal fun java.io.InputStream.readLimited(max: Int): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val length = read(buffer)
        if (length < 0) break
        if (result.size() + length > max) throw IOException("Input exceeds size limit")
        result.write(buffer, 0, length)
    }
    return result.toByteArray()
}

