package org.rankoffline.app

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Optional benchmark against a catalog already installed on an isolated test emulator. */
@RunWith(AndroidJUnit4::class)
class CatalogRealQueryInstrumentedTest {
    @Test fun installedAnimeAndMangaFiltersAndMerge() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Run only on an isolated emulator", Build.HARDWARE.startsWith("ranchu") ||
            Build.HARDWARE.startsWith("goldfish"))
        val manifests = listOf(1L, 2L).mapNotNull { id ->
            File(context.filesDir, "packs/pack-$id/manifest.json")
                .takeIf(File::isFile)?.let { PackManifestParser.parse(it.readText()) }
        }
        assumeTrue("Install Anime and Manga in the isolated emulator first", manifests.size == 2)
        val database = Room.databaseBuilder(context, RankOfflineDatabase::class.java,
            "rankoffline_universal.db").setJournalMode(
            androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        try {
            val repository = MediaRepository(database, RatingRepository(database))
            for (manifest in manifests) {
            val sample = repository.search(manifest, SearchUiState(), false, 200)
                .filterIsInstance<MediaListEntry.Item>().first {
                    it.value.attributes["genre"].orEmpty().isNotEmpty() &&
                        it.value.attributes["year"].orEmpty().isNotEmpty()
                }
            val genre = (sample.value.attributes.getValue("genre").first()
                as CatalogAttributeValue.Text).value
            val year = (sample.value.attributes.getValue("year").first()
                as CatalogAttributeValue.Number).value.toLong().toString()
            val filter = CatalogFilters(mapOf("genre" to
                CatalogFilterValue.Choices(setOf(genre))))
            val combined = CatalogFilters(mapOf(
                "genre" to CatalogFilterValue.Choices(setOf(genre)),
                "year" to CatalogFilterValue.Choices(setOf(year))))
            val checks = listOf(
                "search" to suspend { repository.search(manifest, SearchUiState(), false, 200) },
                "search-filter" to suspend { repository.search(manifest,
                    SearchUiState(filters = filter), false, 200) },
                "search-two-filters" to suspend { repository.search(manifest,
                    SearchUiState(filters = combined), false, 200) },
                "search-merge" to suspend { repository.search(manifest,
                    SearchUiState(), true, 200) },
                "search-merge-filter" to suspend { repository.search(manifest,
                    SearchUiState(filters = filter), true, 200) },
                "search-merge-two-filters" to suspend { repository.search(manifest,
                    SearchUiState(filters = combined), true, 200) },
                "ranking" to suspend { repository.ranking(manifest, RankingUiState(), false, 200) },
                "ranking-merge" to suspend { repository.ranking(manifest, RankingUiState(), true, 200) }
            )
            for ((name, query) in checks) {
                Log.i("RankOfflineRealControls", "pack=${manifest.packId.value} START $name")
                val started = SystemClock.elapsedRealtime()
                val entries = withTimeout(30_000) { query() }
                Log.i("RankOfflineRealControls", "pack=${manifest.packId.value} END $name " +
                    "ms=${SystemClock.elapsedRealtime() - started} count=${entries.size}")
                assertTrue(entries.all { it.packId == manifest.packId })
            }
            }
        } finally { database.close() }
    }
}
