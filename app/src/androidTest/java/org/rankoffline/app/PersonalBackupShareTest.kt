package org.rankoffline.app

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalBackupShareTest {
    @Test
    fun fileProviderSharesOnlyThePrivateBackupCacheThroughContentUri() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, SHARED_BACKUP_DIRECTORY).apply { mkdirs() }
        val file = File(directory, "provider-test.json").apply { writeText("{\"test\":true}") }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.personal-backups",
                file
            )
            assertEquals("content", uri.scheme)
            assertTrue(uri.path.orEmpty().contains("shared_personal_backups"))
            assertEquals(
                "{\"test\":true}",
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun fileProviderSharesPrivateRankingPngThroughContentUri() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, SHARED_RANKING_DIRECTORY).apply { mkdirs() }
        val file = File(directory, "provider-test.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.personal-backups",
                file
            )
            assertEquals("content", uri.scheme)
            assertTrue(uri.path.orEmpty().contains("shared_ranking_cards"))
            assertEquals(listOf<Byte>(1, 2, 3), context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toList()
            })
        } finally {
            file.delete()
        }
    }
}
