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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Optional isolated A/B run with the published and locally generated Series archives. */
@RunWith(AndroidJUnit4::class)
class GroupAliasImportBenchmarkInstrumentedTest {
    @Test fun compareSeriesImportWithAndWithoutGroupAliases() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Only an isolated emulator may run this test", Build.HARDWARE.startsWith("ranchu") ||
            Build.HARDWARE.startsWith("goldfish"))
        val input = requireNotNull(context.getExternalFilesDir(null))
        val names = listOf("rankoffline-alias-before.pack", "rankoffline-alias-after.pack")
        assumeTrue("Both verified Series archives are required", names.all { File(input, it).isFile })
        val results = mutableListOf<Triple<Int, Long, Long>>()
        for ((index, name) in names.withIndex()) {
            val root = File(context.cacheDir, "group-alias-benchmark-$index")
            val databaseName = "group-alias-benchmark-$index.db"
            root.deleteRecursively()
            check(root.mkdirs())
            context.deleteDatabase(databaseName)
            val database = Room.databaseBuilder(context, RankOfflineDatabase::class.java,
                databaseName).setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
            try {
                val installer = PackInstaller(root)
                lateinit var manifest: PackManifest
                val directory = PackArchiveReader.withVerifiedArchive(File(input, name), null) { request, open ->
                    manifest = PackManifestParser.parse(request.manifestJson)
                    installer.install(request, open)
                }
                val started = SystemClock.elapsedRealtime()
                val imported = withTimeout(600_000) {
                    CatalogImporter(database).importIfNeeded(manifest, directory)
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                assertTrue(imported is CatalogImportResult.Imported)
                val aliasCount = database.openHelper.readableDatabase.query(
                    "SELECT COUNT(*) FROM media_group_alias WHERE pack_id=4").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }
                val value = imported as CatalogImportResult.Imported
                assertEquals(54_724L, value.itemCount)
                assertEquals(6_181L, value.groupCount)
                results += Triple(index, elapsed, aliasCount)
                Log.i("RankOfflineGroupAliasBenchmark", "version=${if (index == 0) "v0.2.2" else "v0.2.3"} " +
                    "importMs=$elapsed groupAliases=$aliasCount items=${value.itemCount} groups=${value.groupCount}")
            } finally {
                database.close()
                context.deleteDatabase(databaseName)
                root.deleteRecursively()
            }
        }
        assertEquals(0L, results[0].third)
        assertTrue(results[1].third > 0L)
    }
}
