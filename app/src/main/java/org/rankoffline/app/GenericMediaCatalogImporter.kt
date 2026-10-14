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
        validateMediaCatalogManifest(manifest)
        compressedCatalog.use { input ->
            database.withTransaction {
                if (isCurrent(manifest)) return@withTransaction MediaCatalogImportResult.NoChange
                importCatalog(manifest, input)
            }
        }
    }

    private suspend fun isCurrent(manifest: MediaCatalogManifest): Boolean =
        dao.metadata(manifest.mediaType, METADATA_VERSION) == manifest.catalogVersion &&
            dao.metadata(manifest.mediaType, METADATA_SHA256) == manifest.sha256

    private suspend fun importCatalog(
        manifest: MediaCatalogManifest,
        compressedCatalog: InputStream
    ): MediaCatalogImportResult.Imported {
        val stageName = "media_catalog_seen_${UUID.randomUUID().toString().replace("-", "")}"
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TABLE `$stageName` (" +
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
                batch += record
                parsedCount++
                if (batch.size == batchSize) {
                    writeBatch(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) writeBatch(batch)

            // GZIPInputStream may stop with unread trailing raw bytes. Drain the digest stream so
            // the hash always covers the exact compressed asset supplied by the caller.
            val drainBuffer = ByteArray(8 * 1024)
            while (digestInput.read(drainBuffer) != -1) Unit
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
            dao.upsertMetadata(
                listOf(
                    metadata(manifest, METADATA_VERSION, manifest.catalogVersion),
                    metadata(manifest, METADATA_SHA256, manifest.sha256),
                    metadata(manifest, METADATA_ITEM_COUNT, parsedCount.toString()),
                    metadata(manifest, METADATA_PROVIDER, manifest.provider),
                    metadata(manifest, METADATA_FORMAT_VERSION, manifest.formatVersion.toString())
                )
            )
            return MediaCatalogImportResult.Imported(parsedCount)
        } finally {
            sqlite.execSQL("DROP TABLE IF EXISTS `$stageName`")
        }
    }

    private suspend fun writeBatch(records: List<MediaCatalogRecord>) {
        val newItems = records.map { it.toNewEntity() }
        dao.insertNewItems(newItems)
        val updated = dao.updateCatalogFields(records.map { it.toCatalogFields() })
        if (updated != records.size) {
            throw MediaCatalogImportException("Catalog item identity conflicts with an existing provider item")
        }

        val mediaIds = records.map { it.id }
        dao.deleteGenres(mediaIds)
        dao.deleteCredits(mediaIds)
        dao.deleteAliases(mediaIds)

        val genres = records.flatMap { record ->
            record.genres.distinct().map { MediaGenreEntity(record.id, it) }
        }
        val credits = records.flatMap { record ->
            record.credits.distinct().map { MediaCreditEntity(record.id, it.role, it.name) }
        }
        val aliases = records.flatMap { record ->
            record.aliases.distinctBy { it.title }
                .map { MediaAliasEntity(record.id, it.title, it.language, it.kind) }
        }
        if (genres.isNotEmpty()) dao.insertGenres(genres)
        if (credits.isNotEmpty()) dao.insertCredits(credits)
        if (aliases.isNotEmpty()) dao.insertAliases(aliases)
    }

    private fun metadata(
        manifest: MediaCatalogManifest,
        key: String,
        value: String
    ) = MediaCatalogMetadataEntity(manifest.mediaType, key, value)

    private fun MediaCatalogRecord.toNewEntity() = MediaItemEntity(
        id = id,
        mediaType = mediaType,
        provider = provider,
        providerId = providerId,
        title = title,
        originalTitle = originalTitle,
        year = year,
        startDate = startDate,
        format = format,
        status = status,
        originalLanguage = originalLanguage,
        demographic = demographic,
        partNumber = partNumber,
        popularity = popularity,
        popularityRank = popularityRank,
        remoteCoverRef = remoteCoverRef
    )

    private fun MediaCatalogRecord.toCatalogFields() = MediaCatalogItemFields(
        id = id,
        mediaType = mediaType,
        provider = provider,
        providerId = providerId,
        title = title,
        originalTitle = originalTitle,
        year = year,
        startDate = startDate,
        format = format,
        status = status,
        originalLanguage = originalLanguage,
        demographic = demographic,
        partNumber = partNumber,
        popularity = popularity,
        popularityRank = popularityRank,
        remoteCoverRef = remoteCoverRef
    )

    companion object {
        const val DEFAULT_BATCH_SIZE = 250
        const val METADATA_VERSION = "catalog_version"
        const val METADATA_SHA256 = "catalog_sha256"
        const val METADATA_ITEM_COUNT = "catalog_item_count"
        const val METADATA_PROVIDER = "catalog_provider"
        const val METADATA_FORMAT_VERSION = "catalog_format_version"
    }
}
