package org.rankoffline.app

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.zip.ZipFile

/** ZIP format validation is shared by HTTP downloads and local files. */
internal object PackArchiveReader {
    /** Trust boundary: verify downloaded archive even when the downloader is injected. */
    suspend fun verifyArchiveDigest(file: File, descriptor: PackDownloadDescriptor): Boolean =
        withContext(Dispatchers.IO) {
            if (!file.isFile || file.length() != descriptor.archiveBytes) return@withContext false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) digest.update(buffer, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) } == descriptor.archiveSha256
        }

    fun <T> withVerifiedArchive(
        file: File,
        expected: PackManifest?,
        install: (PackInstallRequest, (PackManifestFile) -> InputStream) -> T
    ): T {
        require(file.isFile && file.length() in 1..PackDownloadDescriptor.MAX_ARCHIVE_BYTES) {
            "Invalid archive length"
        }
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= MAX_ENTRIES) { "Too many ZIP entries" }
            val names = entries.map { it.name }
            require(names.toSet().size == names.size) { "Duplicate ZIP entry" }
            val payloadNames = entries.filterNot { it.isDirectory }.map { it.name }.toSet()
            require("manifest.json" in payloadNames) { "Missing pack manifest" }
            val manifestEntry = requireNotNull(zip.getEntry("manifest.json"))
            require(!manifestEntry.isDirectory && manifestEntry.size in 1..PackManifestParser.MAX_MANIFEST_BYTES) {
                "Invalid pack manifest size"
            }
            val bytes = zip.getInputStream(manifestEntry).use { input ->
                val data = input.readLimited(PackManifestParser.MAX_MANIFEST_BYTES)
                require(data.isNotEmpty())
                data
            }
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val manifest = PackManifestParser.parse(text)
            require(expected == null || expected == manifest) { "Archive manifest differs from registry" }
            val declared = manifest.files.associateBy { it.path }
            require(payloadNames == declared.keys + "manifest.json") { "Undeclared or missing ZIP entries" }
            entries.forEach { entry ->
                if (!entry.isDirectory && entry.name != "manifest.json") {
                    val spec = requireNotNull(declared[entry.name])
                    require(entry.size == spec.bytes) { "ZIP payload length differs from manifest" }
                }
                // Explicit directory entries are allowed only as parents of declared files.
                if (entry.isDirectory) {
                    val directory = entry.name.trimEnd('/') + "/"
                    require(declared.keys.any { it.startsWith(directory) }) { "Unexpected ZIP directory" }
                }
            }
            return install(PackInstallRequest(manifestJson = text)) { spec ->
                zip.getInputStream(requireNotNull(zip.getEntry(spec.path)))
            }
        }
    }

    private fun InputStream.readLimited(limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var used = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            require(n.toLong() <= limit - used) { "Oversized manifest" }
            output.write(buffer, 0, n)
            used += n
        }
        return output.toByteArray()
    }
    private const val MAX_ENTRIES = 40_000
}
