package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RankOfflineAllTest {
    @Test
    fun popularityInterleavesPacksWithoutLosingSourceIdentity() {
        val pages = linkedMapOf(
            MediaType.ANIME to listOf(item(MediaType.ANIME, "a1"), item(MediaType.ANIME, "a2")),
            MediaType.BOOK to listOf(item(MediaType.BOOK, "b1")),
            MediaType.MOVIE to listOf(item(MediaType.MOVIE, "m1"), item(MediaType.MOVIE, "m2"))
        )

        val result = mergeAllCatalogPage(
            mediaTypes = pages.keys.toList(),
            pages = pages,
            sortOrder = CatalogSortOrder.POPULARITY,
            limit = 5,
            cursor = AllCatalogCursor()
        ).entries

        assertEquals(listOf("a1", "b1", "m1", "a2", "m2"), result.map { it.id })
        assertEquals(
            listOf(MediaType.ANIME, MediaType.BOOK, MediaType.MOVIE, MediaType.ANIME, MediaType.MOVIE),
            result.map { it.mediaType }
        )
    }

    @Test
    fun allNavigationLabelIsVirtualAndNeverMasksZeroPackState() {
        assertEquals(
            R.string.media_all,
            searchNavigationLabelResource(false, MediaType.ANIME, allSelected = true)
        )
        assertEquals(
            R.string.nav_search,
            searchNavigationLabelResource(true, MediaType.ANIME, allSelected = true)
        )
    }

    @Test
    fun cursorPaginationDoesNotSkipOrDuplicateInterleavedEntries() {
        val first = mergeAllCatalogPage(
            mediaTypes = listOf(MediaType.ANIME, MediaType.BOOK),
            pages = mapOf(
                MediaType.ANIME to listOf(item(MediaType.ANIME, "a1"), item(MediaType.ANIME, "a2")),
                MediaType.BOOK to listOf(item(MediaType.BOOK, "b1"), item(MediaType.BOOK, "b2"))
            ),
            sortOrder = CatalogSortOrder.POPULARITY,
            limit = 3,
            cursor = AllCatalogCursor()
        )
        val second = mergeAllCatalogPage(
            mediaTypes = listOf(MediaType.ANIME, MediaType.BOOK),
            pages = mapOf(
                MediaType.ANIME to emptyList(),
                MediaType.BOOK to listOf(item(MediaType.BOOK, "b2"))
            ),
            sortOrder = CatalogSortOrder.POPULARITY,
            limit = 3,
            cursor = first.nextCursor
        )

        assertEquals(listOf("a1", "b1", "a2"), first.entries.map { it.id })
        assertEquals(listOf("b2"), second.entries.map { it.id })
        assertEquals(4, (first.entries + second.entries).distinctBy { it.mediaType to it.id }.size)
    }

    @Test
    fun titleCursorUsesDeterministicGlobalOrderingAcrossPageBoundaries() {
        fun titled(type: MediaType, id: String, title: String) =
            MediaListEntry.Item(MediaItem(id = id, mediaType = type, title = title))
        val first = mergeAllCatalogPage(
            listOf(MediaType.ANIME, MediaType.BOOK),
            mapOf(
                MediaType.ANIME to listOf(titled(MediaType.ANIME, "a1", "Alpha"), titled(MediaType.ANIME, "a2", "Zulu")),
                MediaType.BOOK to listOf(titled(MediaType.BOOK, "b1", "Beta"), titled(MediaType.BOOK, "b2", "Zulu"))
            ),
            CatalogSortOrder.TITLE_ASC,
            2,
            AllCatalogCursor()
        )
        val second = mergeAllCatalogPage(
            listOf(MediaType.ANIME, MediaType.BOOK),
            mapOf(
                MediaType.ANIME to listOf(titled(MediaType.ANIME, "a2", "Zulu")),
                MediaType.BOOK to listOf(titled(MediaType.BOOK, "b2", "Zulu"))
            ),
            CatalogSortOrder.TITLE_ASC,
            2,
            first.nextCursor
        )

        assertEquals(listOf("a1", "b1", "a2", "b2"), (first.entries + second.entries).map { it.id })
    }

    @Test
    fun deepCursorPaginationRemainsBoundedAndDeterministic() {
        val mediaTypes = listOf(MediaType.ANIME, MediaType.BOOK, MediaType.MOVIE)
        val source = mediaTypes.associateWith { type ->
            List(1_000) { index -> item(type, "${type.persistedValue}-$index") }
        }
        var cursor = AllCatalogCursor()
        val seen = mutableListOf<Pair<MediaType, String>>()

        repeat(100) {
            val pages = mediaTypes.associateWith { type ->
                val offset = cursor.offsets[type] ?: 0
                source.getValue(type).drop(offset).take(30)
            }
            val page = mergeAllCatalogPage(
                mediaTypes,
                pages,
                CatalogSortOrder.POPULARITY,
                limit = 30,
                cursor
            )
            assertEquals(30, page.entries.size)
            seen += page.entries.map { it.mediaType to it.id }
            cursor = page.nextCursor
        }

        assertEquals(3_000, seen.size)
        assertEquals(3_000, seen.distinct().size)
        assertEquals(mediaTypes.associateWith { 1_000 }, cursor.offsets)
    }

    @Test
    fun allCategoryComparisonRequiresTheSameActiveDefinitionInEveryPack() {
        val anime = DefaultSettings.mediaSettings(MediaType.ANIME)
        val book = DefaultSettings.mediaSettings(MediaType.BOOK)
        val compatible = compatibleAllRatingCategories(
            listOf(MediaType.ANIME, MediaType.BOOK),
            mapOf(MediaType.ANIME to anime, MediaType.BOOK to book)
        )

        assertEquals(listOf("writing", "characters", "worldbuilding"), compatible.map { it.id })
    }

    private fun item(mediaType: MediaType, id: String) =
        MediaListEntry.Item(MediaItem(id = id, mediaType = mediaType, title = id))
}
