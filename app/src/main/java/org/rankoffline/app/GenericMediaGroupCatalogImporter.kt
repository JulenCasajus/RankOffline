package org.rankoffline.app

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface MediaGroupCatalogImportResult {
    data class Imported(val groupCount: Long) : MediaGroupCatalogImportResult
    data object NoChange : MediaGroupCatalogImportResult
}

class GenericMediaGroupCatalogImporter(
    private val database: AnimeDatabase,
    private val batchSize: Int = GenericMediaCatalogImporter.DEFAULT_BATCH_SIZE
) {
    private val groupDao = database.mediaGroupDao()
    private val metadataDao = database.mediaCatalogDao()

    init { require(batchSize > 0) }

    suspend fun importIfNeeded(
        manifest: MediaGroupCatalogManifest,
        compressedCatalog: InputStream
    ): MediaGroupCatalogImportResult = withContext(Dispatchers.IO) {
        validateMediaGroupCatalogManifest(manifest)
        compressedCatalog.use { input ->
            database.withTransaction {
                if (isCurrent(manifest)) return@withTransaction MediaGroupCatalogImportResult.NoChange
                importCatalog(manifest, input)
            }
        }
    }

    private suspend fun isCurrent(manifest: MediaGroupCatalogManifest): Boolean =
        metadataDao.metadata(manifest.mediaType, METADATA_VERSION) == manifest.catalogVersion &&
            metadataDao.metadata(manifest.mediaType, METADATA_SHA256) == manifest.sha256

    private suspend fun importCatalog(
        manifest: MediaGroupCatalogManifest,
        compressedCatalog: InputStream
    ): MediaGroupCatalogImportResult.Imported {
        val stageName = "media_group_catalog_seen_${UUID.randomUUID().toString().replace("-", "")}"
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TABLE `$stageName` (id TEXT NOT NULL PRIMARY KEY, group_type TEXT NOT NULL, " +
                "provider_group_id TEXT NOT NULL, UNIQUE(group_type, provider_group_id))"
        )
        try {
            val seen = sqlite.compileStatement(
                "INSERT INTO `$stageName`(id, group_type, provider_group_id) VALUES(?, ?, ?)"
            )
            val digest = MessageDigest.getInstance("SHA-256")
            val digestInput = DigestInputStream(compressedCatalog, digest)
            val gzipInput = try {
                GZIPInputStream(digestInput)
            } catch (exception: Exception) {
                throw MediaCatalogImportException("Group catalog is not a valid gzip stream", exception)
            }
            val reader = gzipInput.bufferedReader(Charsets.UTF_8)
            val batch = ArrayList<MediaGroupCatalogRecord>(batchSize)
            var parsedCount = 0L
            var lineNumber = 0L
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber++
                if (line.isBlank()) throw MediaCatalogImportException("Blank group record at line $lineNumber")
                val record = try {
                    parseMediaGroupCatalogRecord(line).also {
                        validateMediaGroupCatalogRecord(it, manifest)
                    }
                } catch (exception: Exception) {
                    throw MediaCatalogImportException("Invalid group record at line $lineNumber", exception)
                }
                try {
                    seen.clearBindings()
                    seen.bindString(1, record.id)
                    seen.bindString(2, record.groupType)
                    seen.bindString(3, record.providerGroupId)
                    seen.executeInsert()
                } catch (exception: SQLiteConstraintException) {
                    throw MediaCatalogImportException("Duplicate group identity at line $lineNumber", exception)
                }
                batch += record
                parsedCount++
                if (batch.size == batchSize) {
                    writeBatch(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) writeBatch(batch)

            val drainBuffer = ByteArray(8 * 1024)
            while (digestInput.read(drainBuffer) != -1) Unit
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha != manifest.sha256) {
                throw MediaCatalogImportException("Group catalog SHA-256 mismatch")
            }
            if (parsedCount != manifest.groupCount) {
                throw MediaCatalogImportException(
                    "Group catalog count mismatch: expected ${manifest.groupCount}, got $parsedCount"
                )
            }
            metadataDao.upsertMetadata(
                listOf(
                    metadata(manifest, METADATA_VERSION, manifest.catalogVersion),
                    metadata(manifest, METADATA_SHA256, manifest.sha256),
                    metadata(manifest, METADATA_COUNT, parsedCount.toString()),
                    metadata(manifest, METADATA_PROVIDER, manifest.provider),
                    metadata(manifest, METADATA_FORMAT_VERSION, manifest.formatVersion.toString())
                )
            )
            return MediaGroupCatalogImportResult.Imported(parsedCount)
        } finally {
            sqlite.execSQL("DROP TABLE IF EXISTS `$stageName`")
        }
    }

    private suspend fun writeBatch(records: List<MediaGroupCatalogRecord>) {
        val allMembers = records.flatMap { it.members }.map { it.mediaId }.distinct()
        val existingRows = mutableListOf<MediaIdType>()
        for (memberBatch in allMembers.chunked(SQLITE_SAFE_BATCH)) {
            existingRows += groupDao.mediaIdTypes(memberBatch)
        }
        val existingTypes = existingRows.associate { it.id to it.mediaType }
        records.forEach { record ->
            record.members.forEach { member ->
                if (existingTypes[member.mediaId] != record.mediaType) {
                    throw MediaCatalogImportException(
                        "Missing or wrong-media group member: ${member.mediaId}"
                    )
                }
            }
        }

        groupDao.insertNewGroups(records.map { it.toNewEntity() })
        if (groupDao.updateCatalogFields(records.map { it.toCatalogFields() }) != records.size) {
            throw MediaCatalogImportException("Group identity conflicts with an existing provider group")
        }
        val groupIds = records.map { it.id }
        groupDao.deleteMembers(groupIds)
        groupDao.insertMembers(records.flatMap { record ->
            record.members.map { MediaGroupMemberEntity(record.id, it.mediaId, it.position) }
        })
    }

    private fun MediaGroupCatalogRecord.toNewEntity() = MediaGroupEntity(
        id = id,
        mediaType = mediaType,
        groupType = groupType,
        provider = provider,
        providerGroupId = providerGroupId,
        displayTitle = displayTitle,
        remoteCoverRef = remoteCoverRef
    )

    private fun MediaGroupCatalogRecord.toCatalogFields() = MediaGroupCatalogFields(
        id, mediaType, groupType, provider, providerGroupId, displayTitle, remoteCoverRef
    )

    private fun metadata(manifest: MediaGroupCatalogManifest, key: String, value: String) =
        MediaCatalogMetadataEntity(manifest.mediaType, key, value)

    companion object {
        const val METADATA_VERSION = "group_catalog_version"
        const val METADATA_SHA256 = "group_catalog_sha256"
        const val METADATA_COUNT = "group_catalog_count"
        const val METADATA_PROVIDER = "group_catalog_provider"
        const val METADATA_FORMAT_VERSION = "group_catalog_format_version"
        private const val SQLITE_SAFE_BATCH = 500
    }
}
