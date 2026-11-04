package org.rankoffline.app

import java.io.File
import android.graphics.BitmapFactory
import java.security.MessageDigest
import org.json.JSONObject

data class PackCoverRecord(val canonicalId: String, val relativePath: String)

/** The index is declared and hashed. Missing user-removed cover files are acceptable. */
fun PackManifest.readCoverIndex(installedDirectory: File): List<PackCoverRecord> {
    val declaration = files.firstOrNull { it.path == "metadata/cover_index.json" }
        ?: return emptyList()
    require(declaration.bytes in 1..PackManifestParser.MAX_COVER_INDEX_BYTES)
    val index = safeInstalledFile(installedDirectory, declaration.path) ?: return emptyList()
    require(index.length() == declaration.bytes) { "Cover index byte length mismatch" }
    val indexBytes = index.readBytes()
    require(indexBytes.size.toLong() == declaration.bytes) { "Cover index changed during read" }
    val digest = MessageDigest.getInstance("SHA-256").digest(indexBytes)
        .joinToString("") { "%02x".format(it) }
    require(digest == declaration.sha256) { "Cover index SHA-256 mismatch" }
    val declared = files.mapTo(hashSetOf()) { it.path }
    val entries = JSONObject(String(indexBytes, Charsets.UTF_8)).getJSONArray("covers")
    require(entries.length() <= PackManifestParser.MAX_COVER_INDEX_ENTRIES)
    val records = entries.objects().mapNotNull { value ->
        val path = value.getString("path").also(PackManifestParser::validateDataPath)
        require(path.startsWith("covers/") && path in declared) { "Undeclared cover path" }
        val canonicalId = value.getString("canonicalId")
        require(canonicalId.isNotBlank() && canonicalId.length <= 192)
        if (safeInstalledFile(installedDirectory, path) == null) null
        else PackCoverRecord(canonicalId, path)
    }
    require(records.map { it.canonicalId }.distinct().size == records.size) {
        "Duplicate IDs in cover index"
    }
    return records
}

/** A file must stay inside the pack. Image decoders must separately check its bytes. */
fun PackManifest.resolveShareBackground(
    installedDirectory: File,
    template: PackShareTemplate
): File? {
    val relative = presentation.shareBackgrounds.forTemplate(template) ?: return null
    val expected = files.firstOrNull { it.path == relative } ?: return null
    val file = safeInstalledFile(installedDirectory, relative) ?: return null
    if (file.length() !in 1..PackManifestParser.MAX_SHARE_BACKGROUND_BYTES ||
        file.length() != expected.bytes) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val targetWidth = if (template == PackShareTemplate.TOP_SEVEN) 1080 else 1920
    val targetHeight = if (template == PackShareTemplate.TOP_SEVEN) 1920 else 1080
    // Resolution may exceed the export canvas, but aspect ratio must match its template.
    return file.takeIf { bounds.outWidth > 0 && bounds.outHeight > 0 &&
        bounds.outWidth.toLong() * targetHeight == bounds.outHeight.toLong() * targetWidth }

}

private fun safeInstalledFile(root: File, relative: String): File? {
    val base = root.canonicalFile
    val target = File(base, relative).canonicalFile
    return target.takeIf { it.path.startsWith(base.path + File.separator) && it.isFile }
}
