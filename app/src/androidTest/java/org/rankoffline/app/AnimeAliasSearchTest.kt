package org.rankoffline.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnimeAliasSearchTest {
    private lateinit var database: AnimeDatabase
    private lateinit var repository: AnimeRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AnimeDatabase::class.java).build()
        repository = AnimeRepository(context, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun canonicalEnglishAndNativeAliasesResolveToOneAnime() = runBlocking {
        addAnime("mal:16498", "Shingeki no Kyojin")
        addAnime("mal:52991", "Sousou no Frieren")
        database.animeDao().insertAnimeAliases(
            listOf(
                alias("mal:16498", "Attack on Titan"),
                alias("mal:16498", "進撃の巨人"),
                alias("mal:52991", "Frieren: Beyond Journey's End"),
                alias("mal:52991", "葬送のフリーレン")
            )
        )

        mapOf(
            "Shingeki no Kyojin" to "mal:16498",
            "Attack on Titan" to "mal:16498",
            "進撃の巨人" to "mal:16498",
            "Sousou no Frieren" to "mal:52991",
            "Frieren: Beyond Journey's End" to "mal:52991",
            "葬送のフリーレン" to "mal:52991"
        ).forEach { (query, expectedId) ->
            assertEquals(listOf(expectedId), search(query).map { it.id })
        }
    }

    @Test
    fun canonicalAndAliasMatchesDoNotDuplicateButSharedAliasesReturnBothAnime() = runBlocking {
        addAnime("one", "Attack on Titan")
        addAnime("two", "Another Adaptation")
        database.animeDao().insertAnimeAliases(
            listOf(
                alias("one", "Attack on Titan Final"),
                alias("one", "Shared Name"),
                alias("two", "Shared Name")
            )
        )

        assertEquals(listOf("one"), search("Attack on Titan").map { it.id })
        assertEquals(listOf("one", "two"), search("Shared Name").map { it.id }.sorted())
    }

    @Test
    fun groupedSearchUsesMemberAliasesAndReturnsGroupOnce() = runBlocking {
        addAnime("season-1", "Canonical Season One")
        addAnime("season-2", "Canonical Season Two")
        val db = database.openHelper.writableDatabase
        db.execSQL("INSERT INTO anime_alias VALUES('season-2','Known English Name',NULL,'anime-offline-database')")
        db.execSQL("INSERT INTO anime_group VALUES('group:seasons','SERIES','season-1','Canonical Series')")
        db.execSQL("INSERT INTO anime_group_member VALUES('group:seasons','season-1',1)")
        db.execSQL("INSERT INTO anime_group_member VALUES('group:seasons','season-2',2)")

        val results = search("Known English Name", merge = true)
        assertEquals(listOf("group:seasons"), results.map { it.id })
        assertEquals(2, results.single().memberCount)
    }

    private suspend fun search(query: String, merge: Boolean = false): List<Anime> =
        repository.search(
            query = query,
            sortOrder = CatalogSortOrder.TITLE_ASC,
            mergeSeasonsEnabled = merge
        )

    private fun addAnime(id: String, title: String) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO anime(id,title,cover_resolution_state) VALUES(?,?,'UNKNOWN')",
            arrayOf(id, title)
        )
    }

    private fun alias(animeId: String, title: String) =
        AnimeAliasEntity(animeId, title, null, "anime-offline-database")
}
