package org.rankoffline.app

import android.util.Log
import androidx.room.withTransaction
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface AnimeAliasCatalogImportResult {
    data class Imported(val recordCount: Long, val aliasCount: Long) : AnimeAliasCatalogImportResult
    data object NoChange : AnimeAliasCatalogImportResult
}

class AnimeAliasCatalogImportException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class AnimeAliasCatalogImporter(
    private val database: AnimeDatabase,
    private val batchSize: Int = DEFAULT_BATCH_SIZE
) {
    private val dao = database.animeDao()

    init {
        require(batchSize > 0) { "Anime alias import batch size must be positive" }
    }

    suspend fun importIfNeeded(
        manifest: AnimeAliasCatalogManifest,
        compressedCatalog: InputStream
    ): AnimeAliasCatalogImportResult = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        validateAnimeAliasCatalogManifest(manifest)
        val result = compressedCatalog.use { input ->
            database.withTransaction {
                if (isCurrent(manifest)) return@withTransaction AnimeAliasCatalogImportResult.NoChange
                importCatalog(manifest, input)
            }
        }
        if (BuildConfig.DEBUG) Log.d(
            "RankOfflineCatalog",
            "pack=${OfficialPackIds.Anime.value} operation=alias-import result=${result.javaClass.simpleName} " +
                "records=${manifest.recordCount} aliases=${manifest.aliasCount} " +
                "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000L}"
        )
        result
    }

    private suspend fun isCurrent(manifest: AnimeAliasCatalogManifest): Boolean =
        dao.metadata(METADATA_SOURCE_RELEASE) == manifest.sourceRelease &&
            dao.metadata(METADATA_SHA256) == manifest.sha256

    private suspend fun importCatalog(
        manifest: AnimeAliasCatalogManifest,
        compressedCatalog: InputStream
    ): AnimeAliasCatalogImportResult.Imported {
        val sqlite = database.openHelper.writableDatabase
        // Maintaining the two secondary indexes for every one of the 200k+ alias inserts is
        // substantially slower than rebuilding them once. This remains inside the surrounding
        // Room transaction, so a parse/hash/FK failure restores the previous indexed catalog.
        sqlite.execSQL("DROP INDEX IF EXISTS `idx_anime_alias_anime`")
        sqlite.execSQL("DROP INDEX IF EXISTS `idx_anime_alias_title`")
        dao.deleteAllAnimeAliases()
        var parseNanos = 0L
        var writeNanos = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val digestInput = DigestInputStream(compressedCatalog, digest)
        val gzipInput = try {
            GZIPInputStream(digestInput)
        } catch (exception: Exception) {
            throw AnimeAliasCatalogImportException("Anime alias catalog is not a valid gzip stream", exception)
        }
        val reader = gzipInput.bufferedReader(Charsets.UTF_8)
        val records = ArrayList<AnimeAliasCatalogRecord>(batchSize)
        val seenAnimeIds = hashSetOf<String>()
        var parsedRecords = 0L
        var parsedAliases = 0L
        var lineNumber = 0L
        while (true) {
            val parseStarted = System.nanoTime()
            val line = reader.readLine() ?: break
            lineNumber++
            if (line.isBlank()) {
                throw AnimeAliasCatalogImportException("Blank Anime alias record at line $lineNumber")
            }
            val record = try {
                parseAnimeAliasCatalogRecord(line).also(::validateRecord)
            } catch (exception: Exception) {
                throw AnimeAliasCatalogImportException(
                    "Malformed Anime alias record at line $lineNumber",
                    exception
                )
            }
            if (!seenAnimeIds.add(record.animeId)) {
                throw AnimeAliasCatalogImportException("Duplicate Anime alias record at line $lineNumber")
            }
            records += record
            parsedRecords++
            parsedAliases += record.aliases.size
            parseNanos += System.nanoTime() - parseStarted
            if (records.size == batchSize) {
                val writeStarted = System.nanoTime()
                writeBatch(records)
                writeNanos += System.nanoTime() - writeStarted
                records.clear()
            }
        }
        if (records.isNotEmpty()) {
            val writeStarted = System.nanoTime()
            writeBatch(records)
            writeNanos += System.nanoTime() - writeStarted
        }

        val drainBuffer = ByteArray(8 * 1024)
        while (digestInput.read(drainBuffer) != -1) Unit
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualSha256 != manifest.sha256) {
            throw AnimeAliasCatalogImportException(
                "Anime alias SHA-256 mismatch: expected ${manifest.sha256}, got $actualSha256"
            )
        }
        if (parsedRecords != manifest.recordCount) {
            throw AnimeAliasCatalogImportException(
                "Anime alias record count mismatch: expected ${manifest.recordCount}, got $parsedRecords"
            )
        }
        if (parsedAliases != manifest.aliasCount) {
            throw AnimeAliasCatalogImportException(
                "Anime alias count mismatch: expected ${manifest.aliasCount}, got $parsedAliases"
            )
        }
        val indexStarted = System.nanoTime()
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `idx_anime_alias_anime` ON `anime_alias` (`anime_id`)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `idx_anime_alias_title` ON `anime_alias` (`title`)")
        val indexNanos = System.nanoTime() - indexStarted
        listOf(
            CatalogMetadataEntity(METADATA_SOURCE_RELEASE, manifest.sourceRelease),
            CatalogMetadataEntity(METADATA_SHA256, manifest.sha256),
            CatalogMetadataEntity(METADATA_RECORD_COUNT, parsedRecords.toString()),
            CatalogMetadataEntity(METADATA_ALIAS_COUNT, parsedAliases.toString()),
            CatalogMetadataEntity(METADATA_FORMAT_VERSION, manifest.formatVersion.toString())
        ).forEach { dao.putMetadata(it) }
        if (BuildConfig.DEBUG) Log.d(
            "RankOfflineCatalog",
            "pack=${OfficialPackIds.Anime.value} operation=alias-write records=$parsedRecords " +
                "aliases=$parsedAliases parseMs=${parseNanos / 1_000_000L} " +
                "sqlMs=${writeNanos / 1_000_000L} indexMs=${indexNanos / 1_000_000L}"
        )
        return AnimeAliasCatalogImportResult.Imported(parsedRecords, parsedAliases)
    }

    private suspend fun writeBatch(records: List<AnimeAliasCatalogRecord>) {
        val requestedIds = records.map { it.animeId }
        val existingIds = dao.existingAnimeIds(requestedIds).toHashSet()
        val missingId = requestedIds.firstOrNull { it !in existingIds }
        if (missingId != null) {
            throw AnimeAliasCatalogImportException("Anime alias references missing Anime ID: $missingId")
        }
        val aliases = records.flatMap { record ->
            record.aliases.map { alias ->
                AnimeAliasEntity(record.animeId, alias.title, alias.language, alias.kind)
            }
        }
        if (aliases.isNotEmpty()) dao.insertAnimeAliases(aliases)
    }

    private fun validateRecord(record: AnimeAliasCatalogRecord) {
        require(record.animeId.isNotBlank()) { "Anime ID must not be blank" }
        require(record.aliases.size <= MAX_ALIASES_PER_ANIME) { "Too many aliases for ${record.animeId}" }
        val titles = hashSetOf<String>()
        record.aliases.forEach { alias ->
            require(alias.title.isNotBlank()) { "Anime alias title must not be blank" }
            require(titles.add(alias.title)) { "Duplicate Anime alias title for ${record.animeId}" }
        }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 250
        const val MAX_ALIASES_PER_ANIME = 50
        const val METADATA_SOURCE_RELEASE = "anime_alias_source_release"
        const val METADATA_SHA256 = "anime_alias_sha256"
        const val METADATA_RECORD_COUNT = "anime_alias_record_count"
        const val METADATA_ALIAS_COUNT = "anime_alias_count"
        const val METADATA_FORMAT_VERSION = "anime_alias_format_version"
    }
}
