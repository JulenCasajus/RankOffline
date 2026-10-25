package org.rankoffline.app

import android.util.Log
import androidx.room.withTransaction
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AnimeGroupCatalogImporter(private val database: AnimeDatabase) {
    private val dao = database.animeDao()

    suspend fun importIfNeeded(
        manifest: AnimeGroupCatalogManifest,
        compressedCatalog: InputStream
    ): Boolean = withContext(Dispatchers.IO) {
        validateAnimeGroupCatalogManifest(manifest)
        val startedAt = System.nanoTime()
        val imported = compressedCatalog.use { input ->
            database.withTransaction {
                if (isCurrent(manifest)) return@withTransaction false
                importCatalog(manifest, input)
                true
            }
        }
        if (BuildConfig.DEBUG) Log.d(
            TAG,
            "pack=${OfficialPackIds.Anime.value} operation=group-import result=${if (imported) "Imported" else "NoChange"} " +
                "groups=${manifest.groupCount} members=${manifest.membershipCount} " +
                "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000L}"
        )
        imported
    }

    private suspend fun isCurrent(manifest: AnimeGroupCatalogManifest): Boolean =
        dao.metadata(METADATA_VERSION) == manifest.catalogVersion &&
            dao.metadata(METADATA_SHA256) == manifest.sha256 &&
            dao.metadata(METADATA_COUNT) == manifest.groupCount.toString() &&
            dao.metadata(METADATA_MEMBERSHIP_COUNT) == manifest.membershipCount.toString() &&
            dao.catalogGroupCount().toLong() == manifest.groupCount &&
            dao.catalogGroupMembershipCount().toLong() == manifest.membershipCount &&
            dao.invalidCatalogGroupCount() == 0

    private suspend fun importCatalog(
        manifest: AnimeGroupCatalogManifest,
        compressedCatalog: InputStream
    ) {
        val sqlite = database.openHelper.writableDatabase
        val digest = MessageDigest.getInstance("SHA-256")
        val digestInput = DigestInputStream(compressedCatalog, digest)
        val reader = try {
            GZIPInputStream(digestInput).bufferedReader(Charsets.UTF_8)
        } catch (exception: Exception) {
            throw MediaCatalogImportException("Anime group catalog is not valid gzip", exception)
        }
        val records = ArrayList<AnimeGroupCatalogRecord>(manifest.groupCount.toInt())
        var lineNumber = 0L
        while (true) {
            val line = reader.readLine() ?: break
            lineNumber++
            if (line.isBlank()) throw MediaCatalogImportException("Blank Anime group at line $lineNumber")
            val record = try {
                parseAnimeGroupCatalogRecord(line).also(::validateAnimeGroupCatalogRecord)
            } catch (exception: Exception) {
                throw MediaCatalogImportException("Invalid Anime group at line $lineNumber", exception)
            }
            records += record
        }
        val drain = ByteArray(DEFAULT_BUFFER_SIZE)
        while (digestInput.read(drain) != -1) Unit
        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        require(actualSha == manifest.sha256) { "Anime group SHA-256 mismatch" }
        require(records.size.toLong() == manifest.groupCount) { "Anime group count mismatch" }
        require(records.sumOf { it.members.size }.toLong() == manifest.membershipCount) {
            "Anime group membership count mismatch"
        }
        require(records.map { it.id }.distinct().size == records.size) { "Duplicate Anime group ID" }

        val writeStartedAt = System.nanoTime()
        val existingGroupIds = dao.catalogGroupIds().toHashSet()
        val freshGraph = existingGroupIds.isEmpty() && dao.catalogGroupMembershipCount() == 0
        val insertGroup = sqlite.compileStatement(
            "INSERT OR IGNORE INTO anime_group(id,type,root_anime_id,display_title) VALUES(?,?,?,?)"
        )
        val updateGroup = sqlite.compileStatement(
            "UPDATE anime_group SET type=?,root_anime_id=?,display_title=? WHERE id=? " +
                "AND (type<>? OR root_anime_id<>? OR display_title<>?)"
        )
        val insertMember = sqlite.compileStatement(
            "INSERT INTO anime_group_member(group_id,anime_id,position) VALUES(?,?,?)"
        )
        val stageTable = "anime_group_member_stage"
        val stageMember = if (freshGraph) null else {
            sqlite.execSQL("DROP TABLE IF EXISTS temp.$stageTable")
            sqlite.execSQL(
                "CREATE TEMP TABLE $stageTable(" +
                    "group_id TEXT NOT NULL,anime_id TEXT NOT NULL,position INTEGER NOT NULL," +
                    "PRIMARY KEY(group_id,anime_id)) WITHOUT ROWID"
            )
            sqlite.compileStatement(
                "INSERT INTO $stageTable(group_id,anime_id,position) VALUES(?,?,?)"
            )
        }
        var insertedGroups = 0
        var changedGroups = 0
        records.forEach { record ->
            insertGroup.clearBindings()
            insertGroup.bindString(1, record.id)
            insertGroup.bindString(2, record.type)
            insertGroup.bindString(3, record.rootAnimeId)
            insertGroup.bindString(4, record.displayTitle)
            val inserted = insertGroup.executeInsert() != -1L
            if (inserted) {
                insertedGroups++
            } else {
                require(record.id in existingGroupIds) { "Anime group identity conflict: ${record.id}" }
                updateGroup.clearBindings()
                updateGroup.bindString(1, record.type)
                updateGroup.bindString(2, record.rootAnimeId)
                updateGroup.bindString(3, record.displayTitle)
                updateGroup.bindString(4, record.id)
                updateGroup.bindString(5, record.type)
                updateGroup.bindString(6, record.rootAnimeId)
                updateGroup.bindString(7, record.displayTitle)
                changedGroups += updateGroup.executeUpdateDelete()
            }
            migrateLegacyNormalizedGroupRating(sqlite, record.id, record.type)
            record.members.forEach { member ->
                val destination = stageMember ?: insertMember
                destination.clearBindings()
                destination.bindString(1, record.id)
                destination.bindString(2, member.animeId)
                destination.bindLong(3, member.position.toLong())
                destination.executeInsert()
            }
        }
        if (!freshGraph) {
            // The staging table represents the complete incoming graph. Keep unchanged membership
            // rows in place, update only moved members, insert only new rows, then remove stale rows.
            // This preserves the atomic complete-snapshot behavior without rewriting the graph.
            sqlite.execSQL(
                "UPDATE anime_group_member SET position=(" +
                    "SELECT staged.position FROM $stageTable staged " +
                    "WHERE staged.group_id=anime_group_member.group_id " +
                    "AND staged.anime_id=anime_group_member.anime_id) " +
                    "WHERE EXISTS(SELECT 1 FROM $stageTable staged " +
                    "WHERE staged.group_id=anime_group_member.group_id " +
                    "AND staged.anime_id=anime_group_member.anime_id " +
                    "AND staged.position<>anime_group_member.position)"
            )
            sqlite.execSQL(
                "INSERT INTO anime_group_member(group_id,anime_id,position) " +
                    "SELECT staged.group_id,staged.anime_id,staged.position FROM $stageTable staged " +
                    "WHERE NOT EXISTS(SELECT 1 FROM anime_group_member current " +
                    "WHERE current.group_id=staged.group_id AND current.anime_id=staged.anime_id)"
            )
            sqlite.execSQL(
                "DELETE FROM anime_group_member WHERE NOT EXISTS(" +
                    "SELECT 1 FROM $stageTable staged " +
                    "WHERE staged.group_id=anime_group_member.group_id " +
                    "AND staged.anime_id=anime_group_member.anime_id)"
            )
            sqlite.execSQL("DROP TABLE $stageTable")
        }
        val ids = records.map { it.id }.toSet()
        dao.catalogGroupIds().filterNot(ids::contains).chunked(500).forEach { staleGroupIds ->
            dao.deleteCatalogGroups(staleGroupIds)
        }
        require(dao.catalogGroupCount().toLong() == manifest.groupCount) {
            "Imported Anime group count does not match manifest"
        }
        require(dao.catalogGroupMembershipCount().toLong() == manifest.membershipCount) {
            "Imported Anime membership count does not match manifest"
        }
        require(dao.invalidCatalogGroupCount() == 0) { "Imported Anime graph contains invalid groups" }
        dao.putMetadata(CatalogMetadataEntity(METADATA_VERSION, manifest.catalogVersion))
        dao.putMetadata(CatalogMetadataEntity(METADATA_SHA256, manifest.sha256))
        dao.putMetadata(CatalogMetadataEntity(METADATA_COUNT, manifest.groupCount.toString()))
        dao.putMetadata(
            CatalogMetadataEntity(METADATA_MEMBERSHIP_COUNT, manifest.membershipCount.toString())
        )
        if (BuildConfig.DEBUG) Log.d(
            TAG,
            "pack=${OfficialPackIds.Anime.value} operation=group-write fresh=$freshGraph " +
                "insertedGroups=$insertedGroups changedGroups=$changedGroups " +
                "elapsedMs=${(System.nanoTime() - writeStartedAt) / 1_000_000L}"
        )
    }

    private fun migrateLegacyNormalizedGroupRating(
        sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
        groupId: String,
        normalizedFormat: String
    ) {
        val legacySuffix = when (normalizedFormat) {
            MOVIES_FORMAT_FILTER -> "MOVIE"
            SPECIAL_FORMAT_FILTER -> "OVA"
            else -> return
        }
        val legacyId = groupId.substringBeforeLast(':') + ":$legacySuffix"
        val targetExists = sqlite.query(
            "SELECT 1 FROM group_rating WHERE group_id=? LIMIT 1",
            arrayOf(groupId)
        ).use { it.moveToFirst() }
        if (targetExists) return
        sqlite.execSQL(
            "INSERT OR IGNORE INTO group_rating(group_id,additive_score,quality_score,final_score,notes,status,updated_at,profile_id) " +
                "SELECT ?,additive_score,quality_score,final_score,notes,status,updated_at,profile_id " +
                "FROM group_rating WHERE group_id=?",
            arrayOf(groupId, legacyId)
        )
        sqlite.execSQL(
            "INSERT OR IGNORE INTO group_rating_score(group_id,category_id,score,applicable) " +
                "SELECT ?,category_id,score,applicable FROM group_rating_score " +
                "WHERE group_id=? AND EXISTS(SELECT 1 FROM group_rating WHERE group_id=?)",
            arrayOf(groupId, legacyId, groupId)
        )
    }

    companion object {
        const val METADATA_VERSION = "anime_group_catalog_version"
        const val METADATA_SHA256 = "anime_group_catalog_sha256"
        const val METADATA_COUNT = "anime_group_catalog_count"
        const val METADATA_MEMBERSHIP_COUNT = "anime_group_membership_count"
        private const val TAG = "RankOfflineMerge"
    }
}
