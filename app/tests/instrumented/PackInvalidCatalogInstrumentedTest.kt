package org.rankoffline.app

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackInvalidCatalogInstrumentedTest {
    @Test fun catalogIdentityConflictAbortsAndIsPermanentFailure() = runBlocking {
        assumeTrue(Build.HARDWARE.startsWith("ranchu") || Build.HARDWARE.startsWith("goldfish"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "invalid-catalog-test")
        root.deleteRecursively()
        check(root.mkdirs())
        try {
            val archive = File(root, "invalid.pack")
            ZipOutputStream(archive.outputStream()).use { zip ->
                listOf("manifest.json" to TestFixtures.manifestJson().toByteArray(),
                    "catalog/items.jsonl" to TestFixtures.catalogBytes).forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            val packRoot = File(root, "packs")
            val manager = PackManager(packRoot, File(root, "cache"), PackSourceRegistry(),
                PackArchiveDownloader { _, _, _ -> error("Unexpected download") },
                PackCatalogSynchronizer { _, _ ->
                    throw IllegalArgumentException("Group identity conflict")
                }, PackCatalogCleaner {}, BuildConfig.VERSION_NAME)

            val result = manager.installVerified(PackId(11), archive, TestFixtures.manifest())
            assertEquals(PackOperationResult.INVALID_PACK, result)
            assertEquals(PackWorkDisposition.PERMANENT_FAILURE, result.toWorkDisposition())
            assertFalse(manager.installer.packDirectory(PackId(11)).exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
