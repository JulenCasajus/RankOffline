package org.rankoffline.app

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.room.withTransaction
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        compressedCatalog: InputStream,
        expectedGroupType: String? = null
    ): MediaGroupCatalogImportResult = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        validateMediaGroupCatalogManifest(manifest)
        val result = compressedCatalog.use { input ->
            database.withTransaction {
                if (isCurrent(manifest, expectedGroupType)) {
                    return@withTransaction MediaGroupCatalogImportResult.NoChange
                }
                if (manifest.formatVersion < MEDIA_GROUP_CATALOG_FORMAT_VERSION) {
                    throw MediaCatalogImportException(
                        "Legacy group pack must be replaced; relationship summaries are no longer built on device"
                    )
                }
                importCatalog(manifest, input, expectedGroupType)
            }
        }
        if (BuildConfig.DEBUG) Log.d(
            MERGE_LOG_TAG,
            "pack=${manifest.mediaType} operation=group-import result=${result.javaClass.simpleName} " +
                "groups=${manifest.groupCount} elapsedMs=${groupElapsedMs(startedAt)}"
        )
        result
    }

    internal suspend fun isCurrent(
        manifest: MediaGroupCatalogManifest,
        expectedGroupType: String?
    ): Boolean =
        metadataDao.metadata(manifest.mediaType, METADATA_VERSION) == manifest.catalogVersion &&
            metadataDao.metadata(manifest.mediaType, METADATA_SHA256) == manifest.sha256 &&
            // Metadata alone can survive an interrupted/legacy table rebuild. Re-import when the
            // actual membership graph is absent so merge starts working without an app restart or
            // a manual pack reinstall.
            (if (expectedGroupType == null) groupDao.usableGroupCount(manifest.mediaType)
            else groupDao.groupCount(manifest.mediaType, expectedGroupType)) == manifest.groupCount &&
            (manifest.membershipCount == null || expectedGroupType == null ||
                groupDao.membershipCount(manifest.mediaType, expectedGroupType) == manifest.membershipCount)

    private suspend fun importCatalog(
        manifest: MediaGroupCatalogManifest,
        compressedCatalog: InputStream,
        expectedGroupType: String?
    ): MediaGroupCatalogImportResult.Imported {
        val stageName = "media_group_catalog_seen_${UUID.randomUUID().toString().replace("-", "")}"
        val sqlite = database.openHelper.writableDatabase
        var parseNanos = 0L
        var writeNanos = 0L
        var membershipCount = 0L
        // The staging identities are transaction-local bookkeeping. A TEMP table avoids taking a
        // main-schema lock that would otherwise block unrelated READY-pack reads for the complete
        // group import.
        sqlite.execSQL(
            "CREATE TEMP TABLE `$stageName` (id TEXT NOT NULL PRIMARY KEY, group_type TEXT NOT NULL, " +
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
                val parseStarted = System.nanoTime()
                val record = try {
                    parseMediaGroupCatalogRecord(line).also {
                        validateMediaGroupCatalogRecord(it, manifest)
                        require(expectedGroupType == null || it.groupType == expectedGroupType) {
                            "Unexpected group type ${it.groupType}; expected $expectedGroupType"
                        }
                    }
                } catch (exception: Exception) {
                    throw MediaCatalogImportException("Invalid group record at line $lineNumber", exception)
                }
                parseNanos += System.nanoTime() - parseStarted
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
                membershipCount += record.memberCount
                parsedCount++
                if (batch.size == batchSize) {
                    currentCoroutineContext().ensureActive()
                    val writeStarted = System.nanoTime()
                    writeBatch(batch)
                    writeNanos += System.nanoTime() - writeStarted
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) {
                val writeStarted = System.nanoTime()
                writeBatch(batch)
                writeNanos += System.nanoTime() - writeStarted
            }

            val drainBuffer = ByteArray(8 * 1024)
            while (digestInput.read(drainBuffer) != -1) {
                currentCoroutineContext().ensureActive()
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha != manifest.sha256) {
                throw MediaCatalogImportException("Group catalog SHA-256 mismatch")
            }
            if (parsedCount != manifest.groupCount) {
                throw MediaCatalogImportException(
                    "Group catalog count mismatch: expected ${manifest.groupCount}, got $parsedCount"
                )
            }
            if (manifest.membershipCount != null && membershipCount != manifest.membershipCount) {
                throw MediaCatalogImportException(
                    "Group membership count mismatch: expected ${manifest.membershipCount}, got $membershipCount"
                )
            }
            // The staging identity set is the complete precomputed graph for this pack version.
            // Remove reference rows no longer present without touching the separately keyed
            // media_group_rating tables, so an update never leaves stale merge membership.
            sqlite.execSQL(
                "DELETE FROM media_group WHERE media_type=? AND id NOT IN " +
                    "(SELECT id FROM `$stageName`)",
                arrayOf(manifest.mediaType)
            )
            metadataDao.upsertMetadata(
                listOf(
                    metadata(manifest, METADATA_VERSION, manifest.catalogVersion),
                    metadata(manifest, METADATA_SHA256, manifest.sha256),
                    metadata(manifest, METADATA_COUNT, parsedCount.toString()),
                    metadata(manifest, METADATA_MEMBERSHIP_COUNT, membershipCount.toString()),
                    metadata(manifest, METADATA_PROVIDER, manifest.provider),
                    metadata(manifest, METADATA_FORMAT_VERSION, manifest.formatVersion.toString())
                )
            )
            if (BuildConfig.DEBUG) Log.d(
                MERGE_LOG_TAG,
                "pack=${manifest.mediaType} operation=group-write groups=$parsedCount " +
                    "members=$membershipCount parseMs=${parseNanos / 1_000_000L} " +
                    "sqlMs=${writeNanos / 1_000_000L}"
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

        val existingIds = groupDao.existingGroupIds(records.map(MediaGroupCatalogRecord::id)).toHashSet()
        val newRecords = records.filterNot { it.id in existingIds }
        val existingRecords = records.filter { it.id in existingIds }
        if (newRecords.isNotEmpty()) groupDao.insertNewGroups(newRecords.map { it.toNewEntity() })
        val updated = if (existingRecords.isEmpty()) 0
        else groupDao.updateCatalogFields(existingRecords.map { it.toCatalogFields() })
        if (updated != existingRecords.size) {
            throw MediaCatalogImportException("Group identity conflicts with an existing provider group")
        }
        if (existingIds.isNotEmpty()) groupDao.deleteMembers(existingIds.toList())
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
        representativeMediaId = representativeMediaId,
        memberCount = memberCount,
        popularity = popularity,
        popularityRank = popularityRank,
        remoteCoverRef = remoteCoverRef
    )

    private fun MediaGroupCatalogRecord.toCatalogFields() = MediaGroupCatalogFields(
        id,
        mediaType,
        groupType,
        provider,
        providerGroupId,
        displayTitle,
        representativeMediaId,
        memberCount,
        popularity,
        popularityRank,
        remoteCoverRef
    )

    private fun metadata(manifest: MediaGroupCatalogManifest, key: String, value: String) =
        MediaCatalogMetadataEntity(manifest.mediaType, key, value)

    companion object {
        const val METADATA_VERSION = "group_catalog_version"
        const val METADATA_SHA256 = "group_catalog_sha256"
        const val METADATA_COUNT = "group_catalog_count"
        const val METADATA_MEMBERSHIP_COUNT = "group_catalog_membership_count"
        const val METADATA_PROVIDER = "group_catalog_provider"
        const val METADATA_FORMAT_VERSION = "group_catalog_format_version"
        private const val SQLITE_SAFE_BATCH = 500
        private const val MERGE_LOG_TAG = "RankOfflineMerge"
    }
}

private fun groupElapsedMs(startedAt: Long): Long =
    (System.nanoTime() - startedAt) / 1_000_000L
