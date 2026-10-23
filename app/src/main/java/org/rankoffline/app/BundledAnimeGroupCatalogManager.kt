package org.rankoffline.app

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BundledAnimeGroupCatalogManager(database: AnimeDatabase) {
    private val importer = AnimeGroupCatalogImporter(database)

    suspend fun importIfNeeded(packDirectory: File) = withContext(Dispatchers.IO) {
        val manifestFile = File(packDirectory, MANIFEST_PATH)
        val catalogFile = File(packDirectory, CATALOG_PATH)
        require(manifestFile.isFile && catalogFile.isFile) {
            "Anime pack is missing its precomputed merge graph"
        }
        val manifest = parseAnimeGroupCatalogManifest(manifestFile.readText())
        catalogFile.inputStream().use { importer.importIfNeeded(manifest, it) }
    }

    companion object {
        const val CATALOG_PATH = "groups/anime_groups.jsonl.gzip"
        const val MANIFEST_PATH = "groups/anime_groups.manifest.json"
    }
}
