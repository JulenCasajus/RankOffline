package org.rankoffline.app

import androidx.room.withTransaction
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface CatalogImportResult {
    data class Imported(val itemCount: Long, val groupCount: Long = 0L) : CatalogImportResult
    data object NoChange : CatalogImportResult
}

class CatalogImportException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Imports catalog items and explicitly declared group relations as one Room transaction.
 * Relations are provided by the pack, never inferred from media types or merge options.
 * No network, publisher or content-category knowledge.
 */
class CatalogImporter(
    private val database: RankOfflineDatabase,
    private val batchSize: Int = DEFAULT_BATCH_SIZE
) {
    init { require(batchSize in 1..MAX_BATCH_SIZE) }

    private val dao: MediaCatalogDao get() = database.mediaCatalogDao()

    /** Atomically imports catalog items and declared relations into Room. */
    suspend fun importIfNeeded(
        manifest: PackManifest,
        installedDirectory: File
    ): CatalogImportResult = withContext(Dispatchers.IO) {
        validateInstalledManifest(manifest, installedDirectory)
        database.withTransaction { importInsideTransaction(manifest, installedDirectory) }
    }

    /**
     * For use inside a coordinator-owned Room transaction. Does not start one itself.
     * The caller must serialize installs per pack and confirm PackInstaller only after commit.
     */
    suspend fun importInsideTransaction(
        manifest: PackManifest,
        installedDirectory: File
    ): CatalogImportResult {
        validateInstalledManifest(manifest, installedDirectory)
        val started = System.nanoTime()
        val timings = CatalogImportTimings()
        val fingerprint = manifestFingerprint(installedDirectory)
        val itemCount = importItemsInsideTransaction(manifest, installedDirectory, fingerprint, timings)
        val relationsStarted = System.nanoTime()
        val relations = CatalogRelationWriter(database, batchSize).sync(manifest, installedDirectory, fingerprint)
        timings.relationNanos = System.nanoTime() - relationsStarted
        if (BuildConfig.DEBUG) Log.d("RankOfflineCatalog",
            "pack=${manifest.packId.value} operation=catalog-import totalMs=${elapsedMs(started)} " +
                "parseMs=${timings.parseNanos / 1_000_000L} writeMs=${timings.writeNanos / 1_000_000L} " +
                "relationsMs=${timings.relationNanos / 1_000_000L} fresh=${timings.freshCatalog}")
        if (itemCount == null && !relations.changed) return CatalogImportResult.NoChange
        return CatalogImportResult.Imported(
            itemCount = itemCount ?: dao.itemCount(manifest.packId.value),
            groupCount = relations.groupCount
        )
    }

    /** Cheap Room fingerprint check on ordinary cold starts: no catalog file reading/import. */
    suspend fun isCurrentFast(manifest: PackManifest, directory: File): Boolean =
        withContext(Dispatchers.IO) {
            if (!File(directory, PackInstaller.COMPLETE_MARKER).isFile) return@withContext false
            val fingerprint = manifestFingerprint(directory)
            val id = manifest.packId.value
            dao.metadata(id, METADATA_FINGERPRINT) == fingerprint &&
                dao.metadata(id, METADATA_FORMAT_VERSION) == MEDIA_CATALOG_FORMAT_VERSION.toString() &&
                dao.metadata(id, "group_manifest_sha256") == fingerprint &&
                dao.metadata(id, "group_format_version") == MEDIA_GROUP_CATALOG_FORMAT_VERSION.toString()
        }

    /** Null means item content was already current; group relations are checked independently. */
    private suspend fun importItemsInsideTransaction(
        manifest: PackManifest,
        installedDirectory: File,
        fingerprint: String,
        timings: CatalogImportTimings
    ): Long? {
        val packId = manifest.packId.value
        if (isCurrent(packId, fingerprint)) return null

        val sqlite = database.openHelper.writableDatabase
        // A previous uninstall normally clears these together. A zero item count alone
        // cannot prove that interrupted cleanup left no group or metadata rows behind.
        timings.freshCatalog = dao.itemCount(packId) == 0L &&
            database.mediaGroupDao().groupCountForPack(packId) == 0L &&
            dao.metadata(packId, METADATA_FINGERPRINT) == null &&
            dao.metadata(packId, "group_manifest_sha256") == null
        val stage = "catalog_seen_${UUID.randomUUID().toString().replace("-", "")}" // generated, never user input
        sqlite.execSQL("CREATE TEMP TABLE `$stage` (id TEXT NOT NULL PRIMARY KEY, " +
            "provider TEXT, provider_id TEXT, UNIQUE(provider, provider_id))")
        try {
            val writer = CatalogImportWriter(dao, sqlite, packId, stage, timings.freshCatalog)
            val batch = ArrayList<MediaCatalogItemRows>(batchSize)
            var total = 0L
            var expandedBytes = 0L
            try {
                for (path in manifest.catalog) {
                    val spec = requireNotNull(manifest.files.firstOrNull { it.path == path }) {
                        "Catalog payload not declared in manifest: $path"
                    }
                    val expanded = CatalogPayloadReader.read(spec, installedDirectory) { line, lineNumber ->
                        currentCoroutineContext().ensureActive()
                        val rows = try {
                            val parseStarted = System.nanoTime()
                            parseMediaCatalogRecord(line, manifest.fieldSpecs)
                                .toEntityRows(manifest.packId, manifest.fieldSpecs)
                                .also { timings.parseNanos += System.nanoTime() - parseStarted }
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            throw CatalogImportException("Invalid catalog record in $path at line $lineNumber", error)
                        }
                        writer.markSeen(rows.item, path, lineNumber)
                        batch.add(rows)
                        total = Math.addExact(total, 1L)
                        require(total <= MAX_ITEMS_PER_PACK) { "Catalog exceeds item limit" }
                        if (batch.size == batchSize) {
                            val writeStarted = System.nanoTime()
                            writer.upsert(batch)
                            timings.writeNanos += System.nanoTime() - writeStarted
                            batch.clear()
                        }
                    }
                    expandedBytes = Math.addExact(expandedBytes, expanded)
                    require(expandedBytes <= MAX_PACK_EXPANDED_BYTES) { "Expanded catalog exceeds budget" }
                }
                if (batch.isNotEmpty()) {
                    val writeStarted = System.nanoTime()
                    writer.upsert(batch)
                    timings.writeNanos += System.nanoTime() - writeStarted
                }
                writer.deleteMissing()
                dao.putMetadata(listOf(
                    MediaCatalogMetadataEntity(packId, METADATA_FINGERPRINT, fingerprint),
                    MediaCatalogMetadataEntity(packId, METADATA_ITEM_COUNT, total.toString()),
                    MediaCatalogMetadataEntity(packId, METADATA_FORMAT_VERSION, MEDIA_CATALOG_FORMAT_VERSION.toString())
                ))
                return total
            } finally { writer.close() }
        } finally {
            sqlite.execSQL("DROP TABLE IF EXISTS `$stage`")
        }
    }

    private suspend fun isCurrent(packId: Long, fingerprint: String): Boolean {
        if (dao.metadata(packId, METADATA_FINGERPRINT) != fingerprint) return false
        if (dao.metadata(packId, METADATA_FORMAT_VERSION) != MEDIA_CATALOG_FORMAT_VERSION.toString()) return false
        val expectedCount = dao.metadata(packId, METADATA_ITEM_COUNT)?.toLongOrNull() ?: return false
        return expectedCount >= 0L && dao.itemCount(packId) == expectedCount
    }

    /** Check manifest identity and installed marker BEFORE reading any pack-provided path. */
    private fun validateInstalledManifest(manifest: PackManifest, directory: File) {
        require(directory.isDirectory && !java.nio.file.Files.isSymbolicLink(directory.toPath())) { "Invalid installed pack directory" }
        require(directory.name == manifest.packId.directoryName()) { "Pack directory identity mismatch" }
        val marker = File(directory, PackInstaller.COMPLETE_MARKER)
        require(marker.isFile && !java.nio.file.Files.isSymbolicLink(marker.toPath())) { "Incomplete pack installation" }
        val manifestFile = File(directory, PackInstaller.PACK_MANIFEST)
        require(!java.nio.file.Files.isSymbolicLink(manifestFile.toPath()) && manifestFile.isFile &&
            manifestFile.length() in 1..PackManifestParser.MAX_MANIFEST_BYTES) {
            "Missing or oversized installed manifest"
        }
        require(PackManifestParser.parse(manifestFile.readText(Charsets.UTF_8)) == manifest) {
            "Installed manifest differs from requested manifest"
        }
    }

    private fun manifestFingerprint(directory: File): String {
        val bytes = File(directory, PackInstaller.PACK_MANIFEST).readBytes()
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 400
        private const val MAX_BATCH_SIZE = 500
        private const val MAX_ITEMS_PER_PACK = 2_000_000L
        private const val MAX_PACK_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024
        const val METADATA_FINGERPRINT = "catalog_manifest_sha256"
        const val METADATA_ITEM_COUNT = "catalog_item_count"
        const val METADATA_FORMAT_VERSION = "catalog_format_version"
    }
}

private data class CatalogImportTimings(
    var parseNanos: Long = 0L,
    var writeNanos: Long = 0L,
    var relationNanos: Long = 0L,
    var freshCatalog: Boolean = false
)

private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000L
