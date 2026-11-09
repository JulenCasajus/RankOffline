package org.rankoffline.app

import android.database.sqlite.SQLiteConstraintException
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class RelationSyncResult(val changed: Boolean, val groupCount: Long)

/** Persists pack-declared relationships; never discovers groups or evaluates the Merge toggle. */
internal class CatalogRelationWriter(
    private val database: RankOfflineDatabase,
    batchSize: Int
) {
    private val groupDao = database.mediaGroupDao()
    private val metadataDao = database.mediaCatalogDao()
    private val sqlite = database.openHelper.writableDatabase
    private val groupsPerBatch = batchSize.coerceAtMost(64)

    suspend fun sync(manifest: PackManifest, directory: File, fingerprint: String): RelationSyncResult {
        val packId = manifest.packId.value
        if (isCurrent(packId, fingerprint)) {
            return RelationSyncResult(false, metadataDao.metadata(packId, GROUP_COUNT)!!.toLong())
        }

        val stage = "relation_seen_${UUID.randomUUID().toString().replace("-", "")}" // never user input
        sqlite.execSQL("CREATE TEMP TABLE `$stage` (id TEXT NOT NULL PRIMARY KEY)")
        try {
            val mark = sqlite.compileStatement("INSERT INTO `$stage`(id) VALUES(?)")
            var groupCount = 0L
            var membershipCount = 0L
            var expandedBytes = 0L
            var pendingMembers = 0
            val batch = ArrayList<MediaGroupCatalogRows>(groupsPerBatch)
            try {
                for (path in manifest.groups) {
                    val spec = requireNotNull(manifest.files.firstOrNull { it.path == path }) {
                        "Undeclared group payload: $path"
                    }
                    val expanded = CatalogPayloadReader.read(
                        spec, directory, section = "groups", requireRecords = false
                    ) { json, lineNumber ->
                        currentCoroutineContext().ensureActive()
                        val rows = try {
                            parseMediaGroupCatalogRecord(json, manifest).toEntityRows(manifest)
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            throw CatalogImportException("Invalid group record at $path:$lineNumber", error)
                        }
                        try {
                            mark.clearBindings()
                            mark.bindString(1, rows.group.id)
                            mark.executeInsert()
                        } catch (error: SQLiteConstraintException) {
                            throw CatalogImportException("Duplicate group ID at $path:$lineNumber", error)
                        }
                        groupCount = Math.addExact(groupCount, 1L)
                        membershipCount = Math.addExact(membershipCount, rows.members.size.toLong())
                        require(groupCount <= MAX_GROUPS && membershipCount <= MAX_MEMBERSHIPS) {
                            "Too many group relations in pack"
                        }
                        batch.add(rows)
                        pendingMembers += rows.members.size
                        if (batch.size >= groupsPerBatch || pendingMembers >= MAX_BATCH_MEMBERS) {
                            writeBatch(packId, batch)
                            batch.clear()
                            pendingMembers = 0
                        }
                    }
                    expandedBytes = Math.addExact(expandedBytes, expanded)
                    require(expandedBytes <= MAX_EXPANDED_BYTES) { "Group data exceeds expanded size budget" }
                }
                if (batch.isNotEmpty()) writeBatch(packId, batch)
            } finally {
                mark.close()
            }
            // Remove only obsolete catalog-owned relations; detached personal ratings remain.
            sqlite.execSQL(
                "DELETE FROM media_group WHERE pack_id = ? " +
                    "AND NOT EXISTS (SELECT 1 FROM `$stage` WHERE `$stage`.id = media_group.id)",
                arrayOf(packId)
            )
            metadataDao.putMetadata(listOf(
                MediaCatalogMetadataEntity(packId, GROUP_FINGERPRINT, fingerprint),
                MediaCatalogMetadataEntity(packId, GROUP_COUNT, groupCount.toString()),
                MediaCatalogMetadataEntity(packId, MEMBERSHIP_COUNT, membershipCount.toString()),
                MediaCatalogMetadataEntity(packId, GROUP_FORMAT, MEDIA_GROUP_CATALOG_FORMAT_VERSION.toString())
            ))
            return RelationSyncResult(true, groupCount)
        } finally {
            sqlite.execSQL("DROP TABLE IF EXISTS `$stage`")
        }
    }

    private suspend fun isCurrent(packId: Long, fingerprint: String): Boolean {
        if (metadataDao.metadata(packId, GROUP_FINGERPRINT) != fingerprint) return false
        if (metadataDao.metadata(packId, GROUP_FORMAT) != MEDIA_GROUP_CATALOG_FORMAT_VERSION.toString()) return false
        val expectedGroups = metadataDao.metadata(packId, GROUP_COUNT)?.toLongOrNull() ?: return false
        val expectedMembers = metadataDao.metadata(packId, MEMBERSHIP_COUNT)?.toLongOrNull() ?: return false
        return expectedGroups >= 0 && expectedMembers >= 0 &&
            groupDao.groupCountForPack(packId) == expectedGroups &&
            groupDao.membershipCountForPack(packId) == expectedMembers
    }

    private suspend fun writeBatch(packId: Long, rows: List<MediaGroupCatalogRows>) {
        val referencedIds = rows.asSequence().flatMap { row ->
            sequenceOf(row.representative?.mediaId).filterNotNull() + row.members.asSequence().map { it.mediaId }
        }.toSet()
        val existingItems = HashSet<String>()
        for (chunk in referencedIds.chunked(400)) {
            currentCoroutineContext().ensureActive()
            existingItems.addAll(metadataDao.existingItemIds(packId, chunk))
        }
        require(existingItems.containsAll(referencedIds)) {
            "Group references missing or cross-pack catalog items: ${referencedIds.subtract(existingItems).take(3)}"
        }

        val ids = rows.map { it.group.id }
        val existingGroups = groupDao.existingGroupIds(packId, ids).toSet()
        val fresh = rows.map { it.group }.filterNot { it.id in existingGroups }
        val updated = rows.map { it.group }.filter { it.id in existingGroups }
        if (fresh.isNotEmpty()) {
            val inserted = groupDao.insertNewGroups(fresh)
            require(inserted.size == fresh.size && inserted.none { it == -1L }) {
                "Group identity conflict"
            }
        }
        if (updated.isNotEmpty()) {
            val invalidate = sqlite.compileStatement(
                "UPDATE media_group SET local_cover_path = NULL, cover_resolution_state = 'UNKNOWN' " +
                    "WHERE pack_id = ? AND id = ? AND remote_cover_ref IS NOT ?"
            )
            try {
                updated.forEach { group ->
                    invalidate.clearBindings()
                    invalidate.bindLong(1, packId)
                    invalidate.bindString(2, group.id)
                    if (group.remoteCoverRef == null) invalidate.bindNull(3)
                    else invalidate.bindString(3, group.remoteCoverRef)
                    invalidate.executeUpdateDelete()
                }
            } finally {
                invalidate.close()
            }
            val fields = updated.map { group ->
                MediaGroupCatalogFields(
                    packId = group.packId, id = group.id, groupType = group.groupType,
                    displayTitle = group.displayTitle, provider = group.provider,
                    providerGroupId = group.providerGroupId, popularity = group.popularity,
                    popularityRank = group.popularityRank, remoteCoverRef = group.remoteCoverRef
                )
            }
            require(groupDao.updateCatalogFields(fields) == fields.size) { "Group update conflict" }
        }
        groupDao.replaceRelations(
            packId, ids, rows.flatMap { it.members }, rows.mapNotNull { it.representative }
        )
        // Current group records do not declare attributes; do not retain stale catalog attributes.
        groupDao.replaceAttributes(packId, ids, emptyList())
    }

    companion object {
        private const val MAX_GROUPS = 500_000L
        private const val MAX_MEMBERSHIPS = 10_000_000L
        private const val MAX_BATCH_MEMBERS = 10_000
        private const val MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024
        private const val GROUP_FINGERPRINT = "group_manifest_sha256"
        private const val GROUP_COUNT = "group_record_count"
        private const val MEMBERSHIP_COUNT = "group_membership_count"
        private const val GROUP_FORMAT = "group_format_version"
    }
}
