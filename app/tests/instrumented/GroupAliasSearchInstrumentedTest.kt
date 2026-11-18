package org.rankoffline.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupAliasSearchInstrumentedTest {
    @Test fun importedGroupAliasSearchesWithoutLookingThroughMembers() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "group-alias-search-test/pack-11").also {
            it.deleteRecursively()
            check(it.mkdirs())
        }
        val database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java).build()
        try {
            val items = listOf(
                """{"id":"first","title":"Example — Season 1","aliases":[{"title":"Member only"}]}""",
                """{"id":"second","title":"Example — Season 2"}"""
            ).joinToString("\n", postfix = "\n").toByteArray()
            val groups = """{"id":"example","displayTitle":"Example","members":[{"mediaId":"first"},{"mediaId":"second"}],"aliases":[{"title":"Alternative example"}]}"""
                .plus("\n").toByteArray()
            File(root, "catalog").mkdirs()
            File(root, "groups").mkdirs()
            File(root, "catalog/items.jsonl").writeBytes(items)
            File(root, "groups/groups.jsonl").writeBytes(groups)
            val manifestJson = TestFixtures.manifestJson(catalog = items, merge = true, groups = groups)
            File(root, PackInstaller.PACK_MANIFEST).writeText(manifestJson)
            File(root, PackInstaller.COMPLETE_MARKER).writeText("test")
            val manifest = PackManifestParser.parse(manifestJson)
            CatalogImporter(database).importIfNeeded(manifest, root)
            val repository = MediaRepository(database, RatingRepository(database))
            val matched = repository.search(manifest, SearchUiState(query = "Alternative"), true)
            assertEquals(listOf("Alternative example"), matched.map { it.title })
            val memberOnly = repository.search(manifest, SearchUiState(query = "Member only"), true)
            assertTrue(memberOnly.isEmpty())
            val all = repository.searchAllPage(listOf(manifest),
                mapOf(manifest.packId to SearchUiState(query = "Alternative")),
                mapOf(manifest.packId to true), 200)
            assertEquals(listOf("Alternative example"), all.entries.map { it.title })
            assertEquals("Example", repository.search(manifest, SearchUiState(query = "Example"), true)
                .single().title)
            val updatedGroups = groups.toString(Charsets.UTF_8)
                .replace("Alternative example", "Revised example").toByteArray()
            File(root, "groups/groups.jsonl").writeBytes(updatedGroups)
            val updatedJson = TestFixtures.manifestJson(catalog = items, merge = true,
                groups = updatedGroups)
            File(root, PackInstaller.PACK_MANIFEST).writeText(updatedJson)
            val updatedManifest = PackManifestParser.parse(updatedJson)
            CatalogImporter(database).importIfNeeded(updatedManifest, root)
            assertTrue(repository.search(updatedManifest, SearchUiState(query = "Alternative"), true).isEmpty())
            assertEquals("Revised example", repository.search(updatedManifest,
                SearchUiState(query = "Revised"), true).single().title)
            database.mediaCatalogDao().deleteCatalogReferenceData(manifest.packId.value)
            assertTrue(database.mediaGroupDao().aliasesForGroups(manifest.packId.value,
                listOf("example")).isEmpty())
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }
}
