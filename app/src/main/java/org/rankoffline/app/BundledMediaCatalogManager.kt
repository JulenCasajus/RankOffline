package org.rankoffline.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class CatalogAvailability { LOADING, AVAILABLE, UNAVAILABLE, ERROR }

class BundledMediaCatalogManager(
    context: Context,
    private val database: AnimeDatabase,
    private val bundledCatalogsAvailable: Boolean = BuildConfig.BUNDLED_PRODUCTION_CATALOGS
) {
    private val appContext = context.applicationContext
    private val assets = context.applicationContext.assets
    private val itemImporter = GenericMediaCatalogImporter(database)
    private val groupImporter = GenericMediaGroupCatalogImporter(database)

    /** Lightweight durable check used on healthy startup; it never opens the large gzip files. */
    suspend fun isCurrent(
        mediaType: MediaType,
        packManifest: PackManifest? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val prefix = mediaType.persistedValue
        val installedPack = CategoryPackInstaller(
            File(appContext.filesDir, CategoryPackManager.PACK_ROOT)
        ).installedDirectory(mediaType)
        val itemManifest = catalogText(
            installedPack,
            "catalog/${prefix}_catalog.manifest.json",
            "${prefix}_catalog.manifest.json"
        )?.let(::parseMediaCatalogManifest) ?: return@withContext false
        if (itemManifest.mediaType != prefix || !itemImporter.isCurrent(itemManifest)) {
            return@withContext false
        }
        if (!mediaType.supportsMerge()) return@withContext true
        val groupManifest = catalogText(
            installedPack,
            "groups/${prefix}_groups.manifest.json",
            "${prefix}_groups.manifest.json"
        )?.let(::parseMediaGroupCatalogManifest) ?: return@withContext false
        groupManifest.mediaType == prefix && groupImporter.isCurrent(
            groupManifest,
            packManifest?.groupTypeOrNull()
        )
    }

    suspend fun importOptional(
        mediaType: MediaType,
        packManifest: PackManifest? = null,
        onPhase: (CategoryPackInstallPhase) -> Unit = {}
    ): CatalogAvailability =
        withContext(Dispatchers.IO) {
            val prefix = mediaType.persistedValue
            val installedPack = CategoryPackInstaller(
                File(appContext.filesDir, CategoryPackManager.PACK_ROOT)
            ).installedDirectory(mediaType)
            val itemManifest = catalogInput(installedPack, "catalog/${prefix}_catalog.manifest.json", "${prefix}_catalog.manifest.json")
            val itemCatalog = catalogInput(installedPack, "catalog/${prefix}_catalog.jsonl.gzip", "${prefix}_catalog.jsonl.gzip")
            if (itemManifest == null && itemCatalog == null) {
                return@withContext CatalogAvailability.UNAVAILABLE
            }
            if (itemManifest == null || itemCatalog == null) {
                itemManifest?.close()
                itemCatalog?.close()
                return@withContext CatalogAvailability.ERROR
            }
            val startedAt = System.nanoTime()
            var phase = CategoryPackInstallPhase.IMPORTING_CATALOG
            try {
                val manifest = itemManifest.bufferedReader().use { reader ->
                    parseMediaCatalogManifest(reader.readText())
                }
                require(manifest.mediaType == prefix)
                onPhase(CategoryPackInstallPhase.IMPORTING_CATALOG)
                itemImporter.importIfNeeded(manifest, itemCatalog)

                // Open Library Works are already the logical Book item; there is no group artifact.
                if (!mediaType.supportsMerge()) return@withContext CatalogAvailability.AVAILABLE

                val groupManifest = catalogInput(installedPack, "groups/${prefix}_groups.manifest.json", "${prefix}_groups.manifest.json")
                val groupCatalog = catalogInput(installedPack, "groups/${prefix}_groups.jsonl.gzip", "${prefix}_groups.jsonl.gzip")
                if (groupManifest == null && groupCatalog == null) {
                    return@withContext CatalogAvailability.ERROR
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
                phase = CategoryPackInstallPhase.IMPORTING_GROUPS
                onPhase(CategoryPackInstallPhase.IMPORTING_GROUPS)
                groupImporter.importIfNeeded(
                    parsedGroupManifest,
                    groupCatalog,
                    expectedGroupType = packManifest?.groupTypeOrNull()
                )
                val expectedGroupType = packManifest?.groupTypeOrNull()
                if (expectedGroupType != null) {
                    val importedCount = database.mediaGroupDao().groupCount(prefix, expectedGroupType)
                    require(importedCount == parsedGroupManifest.groupCount) {
                        "Imported $importedCount $expectedGroupType groups; expected ${parsedGroupManifest.groupCount}"
                    }
                }
                if (BuildConfig.DEBUG) Log.d(
                    CATALOG_LOG_TAG,
                    "pack=${mediaType.toPackId().value} operation=runtime-import result=AVAILABLE " +
                        "elapsedMs=${catalogManagerElapsedMs(startedAt)}"
                )
                CatalogAvailability.AVAILABLE
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Log.e(
                    CATALOG_LOG_TAG,
                    "pack=${mediaType.toPackId().value} operation=runtime-import phase=$phase " +
                        "elapsedMs=${catalogManagerElapsedMs(startedAt)} " +
                        "failed=${exception.javaClass.simpleName}: ${exception.message}",
                    exception
                )
                CatalogAvailability.ERROR
            } finally {
                runCatching { itemManifest.close() }
                runCatching { itemCatalog.close() }
            }
        }

    private companion object {
        const val CATALOG_LOG_TAG = "RankOfflineCatalog"
    }

    private fun catalogInput(installedPack: File?, packPath: String, legacyAssetName: String): InputStream? =
        installedPack?.let { File(it, packPath).takeIf(File::isFile)?.inputStream() }
            ?: if (bundledCatalogsAvailable) runCatching { assets.open(legacyAssetName) }.getOrNull()
            else null

    private fun catalogText(installedPack: File?, packPath: String, legacyAssetName: String): String? =
        catalogInput(installedPack, packPath, legacyAssetName)?.bufferedReader()?.use { it.readText() }
}

private fun catalogManagerElapsedMs(startedAt: Long): Long =
    (System.nanoTime() - startedAt) / 1_000_000L
