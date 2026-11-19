package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPresentationTest {
    @Test fun changingSearchTextKeepsMatchingAliasWithoutCanonicalTitleFlicker() {
        val scope = ViewingScope.Pack(PackId(1))
        val entry = MediaListEntry.Item(MediaItem(PackId(1), "id", "Canonical",
            aliases = listOf(MediaItemAlias("Alternative name")),
            searchDisplayTitle = "Alternative name"))
        val page = SearchPageState(scope, listOf(entry), loadingInitial = false,
            query = "Alternat")
        assertEquals("Alternative name", page.visibleItemsFor(scope, "Alternative").single().title)
        assertEquals("Canonical", page.visibleItemsFor(scope, "Canonical").single().title)
        assertEquals("Alternative name", page.visibleItemsFor(scope, "name").single().title)
        assertEquals("Canonical", entry.value.title)
    }

    @Test fun aliasTitleAppearsForOneOrTwoCharactersAndTracksEveryEdit() {
        val scope = ViewingScope.Pack(PackId(1))
        val item = MediaListEntry.Item(MediaItem(PackId(1), "id", "Canonical",
            aliases = listOf(MediaItemAlias("Zephyr name"))))
        val page = SearchPageState(scope, listOf(item), loadingInitial = false)
        assertEquals("Zephyr name", page.visibleItemsFor(scope, "Z").single().title)
        assertEquals("Zephyr name", page.visibleItemsFor(scope, "Ze").single().title)
        assertEquals("Canonical", page.visibleItemsFor(scope, "C").single().title)
        assertEquals("Canonical", page.visibleItemsFor(scope, "").single().title)
    }

    @Test fun groupSearchUsesOnlyGroupTitleAndOwnAliases() {
        val query = CatalogQueries.catalog(TestFixtures.manifest(11, merge = true),
            SearchUiState(query = "Alternative"), merge = true, limit = 200)
        val groupSearch = query.sql.substringAfter("WHERE g.pack_id=?")
            .substringBefore("ORDER BY")
        assertTrue(groupSearch.contains("g.display_title LIKE ?"))
        assertTrue(groupSearch.contains("FROM media_group_alias a"))
        assertFalse(groupSearch.contains("i.title LIKE ?"))
        assertFalse(groupSearch.contains("FROM media_alias"))
    }

    @Test fun groupAliasIsVisibleOnlyDuringMatchingSearch() {
        val scope = ViewingScope.Pack(PackId(1))
        val group = MediaGroup(PackId(1), "group", "Canonical group", "relations",
            aliases = listOf(MediaItemAlias("別名グループ")), searchDisplayTitle = "別名グループ")
        val entry = MediaListEntry.Group(group, null, null, null, null, 2, 0, derived = true)
        val page = SearchPageState(scope, listOf(entry), loadingInitial = false, query = "別名")
        assertEquals("別名グループ", page.visibleItemsFor(scope, "別名").single().title)
        assertEquals("別名グループ", page.visibleItemsFor(scope, "別").single().title)
        assertEquals("別名グループ", page.visibleItemsFor(scope, "グループ").single().title)
        assertEquals("Canonical group", page.visibleItemsFor(scope, "Canonical").single().title)
        assertEquals("Canonical group", group.displayTitle)
    }

    @Test fun oldAndNewGroupRecordsRemainReadable() {
        val manifest = TestFixtures.manifest(11, merge = true)
        val old = """{"id":"g","members":[{"mediaId":"a"}],"displayTitle":"Name"}"""
        val newer = """{"id":"g","members":[{"mediaId":"a"}],"displayTitle":"Name","aliases":[{"title":"別名","language":"ja"}]}"""
        assertTrue(parseMediaGroupCatalogRecord(old, manifest).aliases.isEmpty())
        assertEquals("別名", parseMediaGroupCatalogRecord(newer, manifest).aliases.single().title)
    }
}
