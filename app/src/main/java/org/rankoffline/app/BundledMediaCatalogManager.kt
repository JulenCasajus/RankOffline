package org.rankoffline.app

import android.content.Context
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class CatalogAvailability { LOADING, AVAILABLE, UNAVAILABLE, ERROR }

class BundledMediaCatalogManager(
    context: Context,
    database: AnimeDatabase
) {
    private val assets = context.applicationContext.assets
    private val itemImporter = GenericMediaCatalogImporter(database)
    private val groupImporter = GenericMediaGroupCatalogImporter(database)

    suspend fun importOptional(mediaType: MediaType): CatalogAvailability =
        withContext(Dispatchers.IO) {
            require(mediaType in setOf(MediaType.MANGA, MediaType.MOVIE, MediaType.SERIES))
            val prefix = mediaType.persistedValue
            val itemManifest = optionalAsset("${prefix}_catalog.manifest.json")
            val itemCatalog = optionalAsset("${prefix}_catalog.jsonl.gz")
            if (itemManifest == null && itemCatalog == null) {
                return@withContext CatalogAvailability.UNAVAILABLE
            }
            if (itemManifest == null || itemCatalog == null) {
                itemManifest?.close()
                itemCatalog?.close()
                return@withContext CatalogAvailability.ERROR
            }
            try {
                val manifest = itemManifest.bufferedReader().use { reader ->
                    parseMediaCatalogManifest(reader.readText())
                }
                require(manifest.mediaType == prefix)
                itemImporter.importIfNeeded(manifest, itemCatalog)

                val groupManifest = optionalAsset("${prefix}_groups.manifest.json")
                val groupCatalog = optionalAsset("${prefix}_groups.jsonl.gz")
                if (groupManifest == null && groupCatalog == null) {
                    return@withContext CatalogAvailability.AVAILABLE
                }
                if (groupManifest == null || groupCatalog == null) {
                    groupManifest?.close()
                    groupCatalog?.close()
                    return@withContext CatalogAvailability.ERROR
                }
                val parsedGroupManifest = groupManifest.bufferedReader().use { reader ->
                    parseMediaGroupCatalogManifest(reader.readText())
                }
                require(parsedGroupManifest.mediaType == prefix)
                groupImporter.importIfNeeded(parsedGroupManifest, groupCatalog)
                CatalogAvailability.AVAILABLE
            } catch (_: Exception) {
                CatalogAvailability.ERROR
            } finally {
                runCatching { itemManifest.close() }
                runCatching { itemCatalog.close() }
            }
        }

    private fun optionalAsset(name: String): InputStream? = runCatching { assets.open(name) }.getOrNull()
}
