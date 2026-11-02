package org.rankoffline.app

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

private fun InputStream.sha256AndBytes(): Pair<String, Long> {
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) {
                digest.update(buffer, 0, count)
                bytes = Math.addExact(bytes, count.toLong())
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) } to bytes
}

private fun ZipFile.forEachJsonLine(path: String, action: (String, Long) -> Unit) {
    val raw = getInputStream(requireNotNull(getEntry(path)))
    val decoded = if (path.endsWith(".jsonl.gz")) GZIPInputStream(raw) else raw
    decoded.bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.forEachIndexed { index, line ->
            require(line.isNotEmpty()) { "Empty JSONL line at $path:${index + 1}" }
            action(line, index + 1L)
        }
    }
}

private fun validate(path: String): String {
    val file = File(path)
    lateinit var manifest: PackManifest
    PackArchiveReader.withVerifiedArchive(file, null) { request, openFile ->
        manifest = PackManifestParser.parse(request.manifestJson)
        manifest.files.forEach { spec ->
            val (sha256, bytes) = openFile(spec).sha256AndBytes()
            require(bytes == spec.bytes) { "Payload byte mismatch: ${spec.path}" }
            require(sha256 == spec.sha256) { "Payload SHA-256 mismatch: ${spec.path}" }
        }
    }

    val itemIds = HashSet<String>()
    var items = 0L
    var groups = 0L
    var relationships = 0L
    ZipFile(file).use { archive ->
        manifest.catalog.forEach { payload ->
            archive.forEachJsonLine(payload) { line, number ->
                val record = try {
                    parseMediaCatalogRecord(line, manifest.fieldSpecs)
                } catch (error: Exception) {
                    throw IllegalArgumentException("Kotlin item parser rejected $payload:$number", error)
                }
                require(itemIds.add(record.id)) { "Duplicate item ID: ${record.id}" }
                items++
            }
        }
        manifest.groups.forEach { payload ->
            archive.forEachJsonLine(payload) { line, number ->
                val record = try {
                    parseMediaGroupCatalogRecord(line, manifest)
                } catch (error: Exception) {
                    throw IllegalArgumentException("Kotlin group parser rejected $payload:$number", error)
                }
                require(record.members.all { it.mediaId in itemIds }) {
                    "Group references a missing item at $payload:$number"
                }
                require(record.representativeMediaId == null || record.representativeMediaId in itemIds) {
                    "Group representative is missing at $payload:$number"
                }
                groups++
                relationships += record.members.size
            }
        }
    }
    return "${manifest.packId.value}\t$items\t$groups\t$relationships\t${file.name}"
}

private fun manifestPackId(path: String): PackId {
    lateinit var packId: PackId
    PackArchiveReader.withVerifiedArchive(File(path), null) { request, _ ->
        packId = PackManifestParser.parse(request.manifestJson).packId
    }
    return packId
}

fun main(arguments: Array<String>) {
    require(arguments.isNotEmpty()) { "Pass at least one .pack path" }
    println("packId\titems\tgroups\trelationships\tarchive")
    arguments.forEach { println(validate(it)) }
    System.getenv("RANKOFFLINE_PACK_INDEX")?.takeIf { it.isNotBlank() }?.let { path ->
        val hosts = setOf("github.com", "release-assets.githubusercontent.com",
            "objects.githubusercontent.com", "github-releases.githubusercontent.com")
        val descriptors = PackIndexParser.parse(File(path).readText(), TrustedHttpsHosts(hosts))
        require(descriptors.map { it.packId }.toSet() == arguments.map(::manifestPackId).toSet()) {
            "Remote index and supplied archives differ"
        }
        println("remoteIndex\t${descriptors.size}\t${File(path).name}")
    }
}
