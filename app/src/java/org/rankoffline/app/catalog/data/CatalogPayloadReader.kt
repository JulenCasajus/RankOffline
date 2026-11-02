package org.rankoffline.app

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded JSONL reader with strict UTF-8 and declared compressed-file SHA-256 checks. */
internal object CatalogPayloadReader {
    private const val MAX_LINE_BYTES = 256 * 1024
    private const val MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024

    suspend fun read(
        spec: PackManifestFile,
        installedDirectory: File,
        section: String = "catalog",
        requireRecords: Boolean = true,
        onLine: suspend (String, Long) -> Unit
    ): Long {
        require(section == "catalog" || section == "groups") { "Unsupported payload section" }
        require(spec.path.startsWith("$section/")) { "Payload is not in its declared section" }
        PackManifestParser.validateDataPath(spec.path)
        val file = safeCatalogFile(installedDirectory, spec.path)
        require(file.isFile && file.length() == spec.bytes) { "Catalog payload size mismatch: ${spec.path}" }
        require(spec.path.endsWith(".jsonl") || spec.path.endsWith(".jsonl.gz")) {
            "Unsupported catalog payload format: ${spec.path}"
        }
        return FileInputStream(file).use { raw ->
            val digest = MessageDigest.getInstance("SHA-256")
            val digested = DigestInputStream(raw, digest)
            val data: InputStream = if (spec.path.endsWith(".gz")) {
                try { GZIPInputStream(digested) }
                catch (error: Exception) { throw CatalogImportException("Invalid gzip catalog", error) }
            } else digested

            var totalExpanded = 0L
            var lineNumber = 0L
            val pending = ByteArrayOutputStream()
            val buffered = BufferedInputStream(data)
            suspend fun acceptLine() {
                lineNumber++
                val bytes = pending.toByteArray()
                pending.reset()
                val size = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                require(size > 0) { "Empty JSONL line at ${spec.path}:$lineNumber" }
                val text = try {
                    StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, size)).toString()
                } catch (error: Exception) {
                    throw CatalogImportException("Invalid UTF-8 in ${spec.path}:$lineNumber", error)
                }
                onLine(text, lineNumber)
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val byte = try { buffered.read() }
                catch (error: java.io.IOException) { throw CatalogImportException("Corrupted catalog stream: ${spec.path}", error) }
                if (byte == -1) break
                totalExpanded++
                require(totalExpanded <= MAX_EXPANDED_BYTES) { "Catalog exceeds expanded-byte limit" }
                if (byte == '\n'.code) acceptLine()
                else {
                    require(pending.size() < MAX_LINE_BYTES) { "Oversized JSONL record in ${spec.path}" }
                    pending.write(byte)
                }
            }
            if (pending.size() > 0) acceptLine()
            require(!requireRecords || lineNumber > 0) { "Empty catalog payload: ${spec.path}" }
            // GZIP may stop before the underlying file ends. Hash *all* declared bytes.
            val remainder = ByteArray(DEFAULT_BUFFER_SIZE)
            while (digested.read(remainder) >= 0) currentCoroutineContext().ensureActive()
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(spec.sha256, ignoreCase = true)) { "Catalog SHA-256 mismatch: ${spec.path}" }
            totalExpanded
        }
    }

    private fun safeCatalogFile(directory: File, path: String): File {
        var current = directory
        for (part in path.split('/')) {
            current = File(current, part)
            require(!current.isSymbolicLink()) { "Symbolic links forbidden in pack data" }
        }
        require(current.canonicalPath.startsWith(directory.canonicalPath + File.separator)) {
            "Catalog path escapes installation"
        }
        return current
    }
}
