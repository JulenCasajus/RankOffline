package org.rankoffline.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BundledAnimeAliasCatalogManager(
    context: Context,
    database: AnimeDatabase
) {
    private val assets = context.applicationContext.assets
    private val importer = AnimeAliasCatalogImporter(database)

    suspend fun importIfNeeded(): AnimeAliasCatalogImportResult = withContext(Dispatchers.IO) {
        val manifest = assets.open(MANIFEST_ASSET).bufferedReader().use { reader ->
            parseAnimeAliasCatalogManifest(reader.readText())
        }
        assets.open(CATALOG_ASSET).use { catalog -> importer.importIfNeeded(manifest, catalog) }
    }

    companion object {
        const val CATALOG_ASSET = "anime_aliases.jsonl.gz"
        const val MANIFEST_ASSET = "anime_aliases.manifest.json"
    }
}
