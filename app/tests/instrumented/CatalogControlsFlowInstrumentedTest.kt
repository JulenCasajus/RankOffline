package org.rankoffline.app

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the same StateFlow -> Room -> session cache path used by both Compose lists. */
@RunWith(AndroidJUnit4::class)
class CatalogControlsFlowInstrumentedTest {
    @Test fun repeatedSearchAndRankingControlsReplaceTheVisiblePage() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = File(app.cacheDir, "catalog-controls-flow-test/pack-11")
        root.parentFile?.deleteRecursively()
        check(root.mkdirs())
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        val database = Room.inMemoryDatabaseBuilder(app, RankOfflineDatabase::class.java).build()
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val catalog = File(root, "catalog").also { it.mkdirs() }
            val groups = File(root, "groups").also { it.mkdirs() }
            val itemsBytes = listOf(
                """{"id":"alpha","title":"Alpha","popularityRank":1,"attributes":{"genre":["drama"],"year":2020}}""",
                """{"id":"beta","title":"Beta","popularityRank":2,"attributes":{"genre":["comedy"],"year":2020}}""",
                """{"id":"gamma","title":"Gamma","popularityRank":3,"attributes":{"genre":["drama"],"year":2010}}"""
            ).joinToString("\n", postfix = "\n").toByteArray()
            val groupBytes = """{"id":"pair","members":[{"mediaId":"alpha"},{"mediaId":"beta"}],"displayTitle":"Pair","groupType":"relations","representativeMediaId":"alpha"}"""
                .plus("\n").toByteArray()
            File(catalog, "items.jsonl").writeBytes(itemsBytes)
            File(groups, "groups.jsonl").writeBytes(groupBytes)
            val manifestJson = TestFixtures.manifestJson(catalog = itemsBytes, merge = true, groups = groupBytes)
            File(root, PackInstaller.PACK_MANIFEST).writeText(manifestJson)
            File(root, PackInstaller.COMPLETE_MARKER).writeText("test")
            val manifest = PackManifestParser.parse(manifestJson)
            CatalogImporter(database).importIfNeeded(manifest, root)
            listOf("alpha", "beta", "gamma").forEachIndexed { index, id ->
                database.mediaDao().upsertRating(MediaRatingEntity(11, id, 0.0,
                    9.0 - index, 9.0 - index, "", "done", index.toLong()))
            }
            val packRoot = File(root, "packs").also { it.mkdirs() }
            val manager = PackManager(packRoot, File(root, "archives"), PackSourceRegistry(),
                PackArchiveDownloader { _, _, _ -> PackOperationResult.INVALID_PACK },
                PackCatalogSynchronizer { _, _ -> }, RoomPackCatalogCleaner(database), "0.2.2")
            manager._states.value = mapOf(manifest.packId to PackState(manifest.packId, manifest,
                sourceAvailable = false, installed = true, status = PackRuntimeStatus.READY,
                archiveDownloadBytes = null, storage = PackStorageSnapshot(0, 0)))
            val settings = SettingsRepository(isolated,
                object : AppIconAliasController {
                    override fun activate(icon: AppIcon, previousIcon: AppIcon) = Unit
                }, AppLocaleController { })
            settings.manifests = mapOf(manifest.packId to manifest)
            settings._state.value = AppSettingsState(activePackId = manifest.packId,
                mediaSettings = mapOf(manifest.packId to manifest.resolveSettings(
                    PersonalPackPreferences(mergeEnabled = false))), settingsLoaded = true)
            val scope = ViewingScope.Pack(manifest.packId)
            val controller = CatalogUiController(work, MediaRepository(database, RatingRepository(database)),
                manager, settings, MutableStateFlow(scope), MediaRevisionTracker())
            val searchCollector = work.launch { controller.searchPageState.collect() }
            val rankingCollector = work.launch { controller.rankingPage.collect() }
            try {
                controller.activate(AppTab.SEARCH)
                controller.activate(AppTab.RANKING)
                suspend fun search(expected: List<String>) = withTimeout(8_000) {
                    controller.searchPageState.first { page ->
                        page.scope == scope && !page.loadingInitial && page.items.map { it.id } == expected
                    }
                }
                suspend fun ranking(expected: List<String>) = withTimeout(8_000) {
                    controller.rankingPage.first { page ->
                        page.result.scope == scope && !page.result.loading &&
                            page.result.items.map { it.id } == expected
                    }
                }
                search(listOf("alpha", "beta", "gamma"))
                ranking(listOf("alpha", "beta", "gamma"))
                val drama = CatalogFilters(mapOf("genre" to CatalogFilterValue.Choices(setOf("drama"))))
                controller.searchFilters(drama)
                search(listOf("alpha", "gamma"))
                controller.rankingFilters(drama)
                ranking(listOf("alpha", "gamma"))
                settings.updateMergeEnabled(true)
                search(listOf("pair", "gamma"))
                ranking(listOf("pair", "gamma"))
                controller.searchSort(CatalogSortOrder.TITLE_DESC)
                search(listOf("pair", "gamma"))
                controller.toggleRankingDirection()
                ranking(listOf("gamma", "pair"))
                settings.updateMergeEnabled(false)
                search(listOf("gamma", "alpha"))
                ranking(listOf("gamma", "alpha"))
                controller.searchFilters(CatalogFilters())
                controller.rankingFilters(CatalogFilters())
                search(listOf("gamma", "beta", "alpha"))
                ranking(listOf("gamma", "beta", "alpha"))
                val year2020 = CatalogFilters(mapOf("year" to
                    CatalogFilterValue.Choices(setOf("2020"))))
                controller.searchFilters(year2020)
                controller.rankingFilters(year2020)
                search(listOf("beta", "alpha"))
                ranking(listOf("beta", "alpha"))
                val combined = CatalogFilters(mapOf(
                    "genre" to CatalogFilterValue.Choices(setOf("drama")),
                    "year" to CatalogFilterValue.Choices(setOf("2020"))))
                controller.searchFilters(combined)
                controller.rankingFilters(combined)
                search(listOf("alpha"))
                ranking(listOf("alpha"))
                // A rapid succession must publish the final query, not an older cancelled one.
                repeat(8) { index ->
                    controller.searchSort(if (index % 2 == 0) CatalogSortOrder.TITLE_ASC
                        else CatalogSortOrder.POPULARITY)
                    controller.toggleRankingDirection()
                }
                controller.searchFilters(CatalogFilters())
                controller.rankingFilters(CatalogFilters())
                search(listOf("alpha", "beta", "gamma"))
                ranking(listOf("gamma", "beta", "alpha"))
                assertEquals(0, controller.searchByPack.value[manifest.packId]?.filters?.activeCount)

                database.mediaDao().upsertRating(MediaRatingEntity(11, "alpha", 0.0,
                    1.0, 1.0, "", "done", 100))
                controller.ratingChanged(CatalogEntryKey(manifest.packId, "alpha", CatalogEntryKind.ITEM),
                    RatingDraft(finalScore = 1.0, qualityScore = 1.0))
                settings.updateMergeEnabled(true)
                withTimeout(8_000) {
                    controller.searchPageState.first { page ->
                        page.items.filterIsInstance<MediaListEntry.Group>()
                            .singleOrNull()?.finalScore == 4.5
                    }
                }
                withTimeout(8_000) {
                    controller.rankingPage.first { page ->
                        page.result.items.firstOrNull()?.let { it.id == "pair" && it.finalScore == 4.5 } == true
                    }
                }
                Unit
            } finally {
                searchCollector.cancel()
                rankingCollector.cancel()
            }
        } finally {
            work.cancel()
            database.close()
            root.parentFile?.deleteRecursively()
        }
    }
}
