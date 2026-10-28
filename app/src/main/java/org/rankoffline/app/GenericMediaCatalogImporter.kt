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

sealed interface MediaCatalogImportResult {
    data class Imported(val itemCount: Long) : MediaCatalogImportResult
    data object NoChange : MediaCatalogImportResult
}

class MediaCatalogImportException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class GenericMediaCatalogImporter(
    private val database: AnimeDatabase,
    private val batchSize: Int = DEFAULT_BATCH_SIZE
) {
    private val dao = database.mediaCatalogDao()

    init {
        require(batchSize > 0) { "Catalog import batch size must be positive" }
    }

    suspend fun importIfNeeded(
        manifest: MediaCatalogManifest,
        compressedCatalog: InputStream
    ): MediaCatalogImportResult = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        validateMediaCatalogManifest(manifest)
        val result = compressedCatalog.use { input ->
            database.withTransaction {
                if (isCurrent(manifest)) return@withTransaction MediaCatalogImportResult.NoChange
                importCatalog(manifest, input)
            }
        }
        if (BuildConfig.DEBUG) Log.d(
            CATALOG_LOG_TAG,
            "pack=${manifest.mediaType} operation=catalog-import result=${result.javaClass.simpleName} " +
                "items=${manifest.itemCount} elapsedMs=${catalogElapsedMs(startedAt)}"
        )
        result
    }

    internal suspend fun isCurrent(manifest: MediaCatalogManifest): Boolean =
        dao.metadata(manifest.mediaType, METADATA_VERSION) == manifest.catalogVersion &&
            dao.metadata(manifest.mediaType, METADATA_SHA256) == manifest.sha256 &&
            dao.metadata(manifest.mediaType, METADATA_ITEM_COUNT) == manifest.itemCount.toString() &&
            dao.catalogItemCount(manifest.mediaType) == manifest.itemCount

    private suspend fun importCatalog(
        manifest: MediaCatalogManifest,
        compressedCatalog: InputStream
    ): MediaCatalogImportResult.Imported {
        val stageName = "media_catalog_seen_${UUID.randomUUID().toString().replace("-", "")}"
        val sqlite = database.openHelper.writableDatabase
        val timings = ImportTimings()
        val freshCatalog = dao.catalogItemCount(manifest.mediaType) == 0L
        // This identity set is private to the current write connection. Keeping it in SQLite's
        // temporary schema avoids holding a main-schema lock for the whole import, so WAL readers
        // can continue using already READY packs while this transaction remains atomic.
        sqlite.execSQL(
            "CREATE TEMP TABLE `$stageName` (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "media_type TEXT NOT NULL, " +
                "provider TEXT NOT NULL, " +
                "provider_id TEXT NOT NULL, " +
                "UNIQUE(media_type, provider, provider_id))"
        )
        try {
            val seen = sqlite.compileStatement(
                "INSERT INTO `$stageName`(id, media_type, provider, provider_id) VALUES(?, ?, ?, ?)"
            )
            val digest = MessageDigest.getInstance("SHA-256")
            val digestInput = DigestInputStream(compressedCatalog, digest)
            val gzipInput = try {
                GZIPInputStream(digestInput)
            } catch (exception: Exception) {
                throw MediaCatalogImportException("Catalog is not a valid gzip stream", exception)
            }
            val reader = gzipInput.bufferedReader(Charsets.UTF_8)
            val batch = ArrayList<MediaCatalogRecord>(batchSize)
            var parsedCount = 0L
            var lineNumber = 0L
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber++
                if (line.isBlank()) {
                    throw MediaCatalogImportException("Blank JSONL record at line $lineNumber")
                }
                val parseStarted = System.nanoTime()
                val record = try {
                    parseMediaCatalogRecord(line)
                } catch (exception: Exception) {
                    throw MediaCatalogImportException("Malformed JSONL record at line $lineNumber", exception)
                }
                try {
                    validateMediaCatalogRecord(record, manifest)
                    seen.clearBindings()
                    seen.bindString(1, record.id)
                    seen.bindString(2, record.mediaType)
                    seen.bindString(3, record.provider)
                    seen.bindString(4, record.providerId)
                    seen.executeInsert()
                } catch (exception: SQLiteConstraintException) {
                    throw MediaCatalogImportException(
                        "Duplicate catalog ID or provider identity at line $lineNumber",
                        exception
                    )
                } catch (exception: IllegalArgumentException) {
                    throw MediaCatalogImportException(
                        "Invalid catalog record at line $lineNumber: ${exception.message}",
                        exception
                    )
                }
                timings.parseNanos += System.nanoTime() - parseStarted
                batch += record
                parsedCount++
                if (batch.size == batchSize) {
                    currentCoroutineContext().ensureActive()
                    writeBatch(batch, timings, freshCatalog)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) writeBatch(batch, timings, freshCatalog)

            // GZIPInputStream may stop with unread trailing raw bytes. Drain the digest stream so
            // the hash always covers the exact compressed asset supplied by the caller.
            val drainBuffer = ByteArray(8 * 1024)
            while (digestInput.read(drainBuffer) != -1) {
                currentCoroutineContext().ensureActive()
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha256 != manifest.sha256) {
                throw MediaCatalogImportException(
                    "Catalog SHA-256 mismatch: expected ${manifest.sha256}, got $actualSha256"
                )
            }
            if (parsedCount != manifest.itemCount) {
                throw MediaCatalogImportException(
                    "Catalog item count mismatch: expected ${manifest.itemCount}, got $parsedCount"
                )
            }
            val reconcileStarted = System.nanoTime()
            // The pack catalog is the complete reference snapshot. Personal rows are keyed by
            // canonical ID without a catalog foreign key, so stale reference rows can be removed
            // safely while ratings remain available for a future reappearance of that ID.
            sqlite.execSQL(
                "DELETE FROM media_item WHERE media_type=? AND id NOT IN " +
                    "(SELECT id FROM `$stageName`)",
                arrayOf(manifest.mediaType)
            )
            timings.reconcileSqlNanos += System.nanoTime() - reconcileStarted
            dao.upsertMetadata(
                listOf(
                    metadata(manifest, METADATA_VERSION, manifest.catalogVersion),
                    metadata(manifest, METADATA_SHA256, manifest.sha256),
                    metadata(manifest, METADATA_ITEM_COUNT, parsedCount.toString()),
                    metadata(manifest, METADATA_PROVIDER, manifest.provider),
                    metadata(manifest, METADATA_FORMAT_VERSION, manifest.formatVersion.toString())
                )
            )
            if (BuildConfig.DEBUG) Log.d(
                CATALOG_LOG_TAG,
                "pack=${manifest.mediaType} operation=catalog-write items=$parsedCount " +
                    "parseMs=${timings.parseNanos / 1_000_000L} " +
                    "itemSqlMs=${timings.itemSqlNanos / 1_000_000L} " +
                    "auxSqlMs=${timings.auxSqlNanos / 1_000_000L} " +
                    "reconcileSqlMs=${timings.reconcileSqlNanos / 1_000_000L} fresh=$freshCatalog"
            )
            return MediaCatalogImportResult.Imported(parsedCount)
        } finally {
            sqlite.execSQL("DROP TABLE IF EXISTS `$stageName`")
        }
    }

    private suspend fun writeBatch(
        records: List<MediaCatalogRecord>,
        timings: ImportTimings,
        freshCatalog: Boolean
    ) {
        val sqlite = database.openHelper.writableDatabase
        val itemStarted = System.nanoTime()
        val existingIds = if (freshCatalog) emptySet()
        else dao.existingMediaIds(records.map(MediaCatalogRecord::id)).toHashSet()
        val newRecords = if (freshCatalog) records else records.filterNot { it.id in existingIds }
        val existingRecords = if (freshCatalog) emptyList() else records.filter { it.id in existingIds }
        if (newRecords.isNotEmpty()) {
            if (freshCatalog) insertFreshItems(sqlite, newRecords)
            else dao.insertNewItems(newRecords.map { it.toNewEntity() })
        }
        val updated = if (existingRecords.isEmpty()) 0
        else dao.updateCatalogFields(existingRecords.map { it.toCatalogFields() })
        if (updated != existingRecords.size) {
            throw MediaCatalogImportException("Catalog item identity conflicts with an existing provider item")
        }
        timings.itemSqlNanos += System.nanoTime() - itemStarted

        val auxiliaryStarted = System.nanoTime()
        if (existingIds.isNotEmpty()) {
            val mediaIds = existingIds.toList()
            dao.deleteGenres(mediaIds)
            dao.deleteCredits(mediaIds)
            dao.deleteAliases(mediaIds)
        }

        val genres = records.flatMap { record ->
            record.genres.distinct().map { listOf(record.id, it) }
        }
        val credits = records.flatMap { record ->
            record.credits.distinct().map { listOf(record.id, it.role, it.name) }
        }
        val aliases = records.flatMap { record ->
            record.aliases.distinctBy { it.title }
                .map { listOf(record.id, it.title, it.language, it.kind) }
        }
        executeMultiRowInsert(sqlite, "media_genre", listOf("media_id", "genre"), genres)
        executeMultiRowInsert(
            sqlite,
            "media_credit",
            listOf("media_id", "role", "name"),
            credits
        )
        executeMultiRowInsert(
            sqlite,
            "media_alias",
            listOf("media_id", "title", "language", "kind"),
            aliases
        )
        timings.auxSqlNanos += System.nanoTime() - auxiliaryStarted
    }

    /**
     * A fresh pack has no local cover state to preserve. Multi-row INSERTs substantially reduce
     * SQLite VM round trips while staying below Android's conservative bind-variable limit.
     * Updates keep using Room's partial entity update so user-owned/local columns are untouched.
     */
    private fun insertFreshItems(
        sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
        records: List<MediaCatalogRecord>
    ) {
        executeMultiRowInsert(
            sqlite,
            "media_item",
            listOf(
                "id", "media_type", "provider", "provider_id", "title", "original_title",
                "year", "start_date", "format", "status", "original_language", "demographic",
                "part_number", "popularity", "popularity_rank", "remote_cover_ref"
            ),
            records.map { record ->
                listOf(
                    record.id, record.mediaType, record.provider, record.providerId, record.title,
                    record.originalTitle, record.year, record.startDate, record.format, record.status,
                    record.originalLanguage, record.demographic, record.partNumber,
                    record.popularity, record.popularityRank, record.remoteCoverRef
                )
            }
        )
    }

    private fun executeMultiRowInsert(
        sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        columns: List<String>,
        rows: List<List<Any?>>
    ) {
        if (rows.isEmpty()) return
        require(rows.all { it.size == columns.size }) { "Invalid $table insert row width" }
        val rowsPerStatement = (MAX_SQL_BIND_ARGUMENTS / columns.size).coerceAtLeast(1)
        rows.chunked(rowsPerStatement).forEach { chunk ->
            val placeholders = List(chunk.size) {
                List(columns.size) { "?" }.joinToString(prefix = "(", postfix = ")")
            }.joinToString(",")
            val sql = "INSERT INTO `$table`(" + columns.joinToString(",") { "`$it`" } +
                ") VALUES $placeholders"
            sqlite.execSQL(sql, chunk.flatten().toTypedArray())
        }
    }

    private fun metadata(
        manifest: MediaCatalogManifest,
        key: String,
        value: String
    ) = MediaCatalogMetadataEntity(manifest.mediaType, key, value)

    companion object {
        const val DEFAULT_BATCH_SIZE = 500
        private const val MAX_SQL_BIND_ARGUMENTS = 900
        const val METADATA_VERSION = "catalog_version"
        const val METADATA_SHA256 = "catalog_sha256"
        const val METADATA_ITEM_COUNT = "catalog_item_count"
        const val METADATA_PROVIDER = "catalog_provider"
        const val METADATA_FORMAT_VERSION = "catalog_format_version"
        private const val CATALOG_LOG_TAG = "RankOfflineCatalog"
    }

    private data class ImportTimings(
        var parseNanos: Long = 0L,
        var itemSqlNanos: Long = 0L,
        var auxSqlNanos: Long = 0L,
        var reconcileSqlNanos: Long = 0L
    )
}

private fun catalogElapsedMs(startedAt: Long): Long =
    (System.nanoTime() - startedAt) / 1_000_000L
