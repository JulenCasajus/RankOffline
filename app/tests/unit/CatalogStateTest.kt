package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogStateTest {
    @Test fun pageAccumulatorAdvancesByRowsReadAndDeduplicatesQualifiedIdentity() {
        val one = MediaItem(PackId(1), "same", "One")
        val two = MediaItem(PackId(2), "same", "Two")
        val initial = SearchPageAccumulator<MediaItem>().append(listOf(one, two), 2, MediaItem::key)
        val next = initial.append(listOf(one, MediaItem(PackId(1), "new", "New")), 2, MediaItem::key)
        assertEquals(4, next.nextOffset)
        assertEquals(3, next.items.size)
        assertTrue(next.hasMore)
    }

    @Test fun shortPageMarksPaginationExhausted() {
        val page = SearchPageAccumulator<String>().append(listOf("a"), 50) {
            CatalogEntryKey(PackId(1), it, CatalogEntryKind.ITEM)
        }
        assertFalse(page.hasMore)
        assertEquals(1, page.nextOffset)
    }

    @Test fun sortDirectionInvertsWithoutChangingCriterion() {
        assertEquals(CatalogSortOrder.POPULARITY_REVERSE, CatalogSortOrder.POPULARITY.inverted())
        assertEquals(CatalogSortOrder.TITLE_DESC, CatalogSortOrder.TITLE_ASC.inverted())
        assertEquals(CatalogSortOrder.TITLE_ASC, CatalogSortOrder.TITLE_DESC.inverted())
    }

    @Test fun manifestCapabilitiesRemoveStaleSortAndFilterSelections() {
        val manifest = TestFixtures.manifest().copy(
            capabilities = TestFixtures.manifest().capabilities.copy(popularity = false),
            filters = emptyList()
        )
        val state = SearchUiState(sortOrder = CatalogSortOrder.POPULARITY,
            filters = CatalogFilters(mapOf("genre" to CatalogFilterValue.Choices(setOf("drama")))))
            .forManifest(manifest)
        assertEquals(CatalogSortOrder.TITLE_ASC, state.sortOrder)
        assertTrue(state.filters.selections.isEmpty())
    }

    @Test fun incompatibleAllCategoriesAreExcluded() {
        val definition = TestFixtures.ratingDefinition()
        val one = MediaSettings(true, definition.config, definition.profiles, false)
        val renamed = one.copy(ratingConfig = RatingSystemConfig(
            definition.config.categories.map { if (it.id == "writing") it.copy(name = "Other") else it }))
        val result = compatibleAllRatingCategories(listOf(PackId(1), PackId(2)),
            mapOf(PackId(1) to one, PackId(2) to renamed))
        assertEquals(listOf("visuals"), result.map { it.id })
    }

    @Test fun packScopedPageRejectsRowsFromAnotherPack() {
        val wrong = MediaListEntry.Item(MediaItem(PackId(2), "x", "X"))
        assertThrows(IllegalArgumentException::class.java) {
            SearchPageState(ViewingScope.Pack(PackId(1)), listOf(wrong), loadingInitial = false)
        }
    }

    @Test fun navigationReselectionIsDistinctFromSwitchingTabs() {
        assertEquals(BottomNavigationAction.RESELECT, bottomNavigationAction(1, 1))
        assertEquals(BottomNavigationAction.SWITCH, bottomNavigationAction(1, 2))
    }

    @Test fun subtitlesFollowManifestOrderAndKeepAllDeclaredValues() {
        val entry = MediaListEntry.Item(MediaItem(PackId(1), "item", "Item", attributes = mapOf(
            "year" to listOf(CatalogAttributeValue.Number(2024.0)),
            "genre" to listOf(CatalogAttributeValue.Text("Drama"), CatalogAttributeValue.Text("Mystery")),
            "studio" to listOf(CatalogAttributeValue.Text("Studio"))
        )))

        val fields = listOf(
            PackFieldDefinition("genre", CatalogFieldType.CHOICE, true, mapOf("en" to "Genre"), emptySet()),
            PackFieldDefinition("year", CatalogFieldType.INTEGER, false, mapOf("en" to "Year"), emptySet()),
            PackFieldDefinition("studio", CatalogFieldType.TEXT, false, mapOf("en" to "Studio"), emptySet())
        )
        assertEquals("Drama · Mystery · 2024 · Studio", entry.subtitleText(fields))
        assertEquals(listOf("Drama", "Mystery"), entry.subtitleParts(fields, "en").first().values)
        assertEquals(false, entry.subtitleParts(fields, "en").first().compact)
    }

    @Test fun longMultiValueSubtitleIsCompactWithoutLosingDistinctValues() {
        val field = PackFieldDefinition("genre", CatalogFieldType.TEXT, true,
            mapOf("en" to "Genre", "es" to "Género"), emptySet())
        val entry = MediaListEntry.Item(MediaItem(PackId(1), "item", "Item", attributes = mapOf(
            "genre" to listOf("Action", "Drama", "Fantasy", "Mystery", "Action")
                .map(CatalogAttributeValue::Text)
        )))
        val part = entry.subtitleParts(listOf(field), "es").single()
        assertEquals("Género", part.label)
        assertEquals(listOf("Action", "Drama", "Fantasy", "Mystery"), part.values)
        assertEquals(true, part.compact)
    }
}
