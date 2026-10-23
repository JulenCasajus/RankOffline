package org.rankoffline.app

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteSearchProviderTest {
    @Test
    fun mangaSearchMapsProviderUuidAndUsesBoundedPage() = runBlocking {
        var requestedPath = ""
        val provider = MangaDexRemoteSearchProvider { path, parameters ->
            requestedPath = path
            assertEquals("Berserk", parameters.first { it.first == "title" }.second)
            JSONObject(MANGA_RESPONSE)
        }

        val page = provider.search("Berserk", 0)

        assertEquals("/manga", requestedPath)
        assertEquals("manga:mangadex:32d76d19-8a05-4db0-9fc2-e0b0648fe9d0", page.records.single().id)
        assertEquals(1, page.nextOffset)
        assertFalse(page.hasMore)
    }

    @Test
    fun bookSearchKeepsWorkIdentityAndRejectsEditionIdentity() = runBlocking {
        val provider = OpenLibraryRemoteSearchProvider { _, _ -> JSONObject(BOOK_RESPONSE) }

        val page = provider.search("Fox", 0)

        assertEquals(listOf("book:openlibrary:OL45804W"), page.records.map { it.id })
        assertEquals("Roald Dahl", page.records.single().credits.single().name)
        assertEquals(
            "openlibrary:v1|id=123|isbn13=9780140328721|isbn10=0140328726|" +
                "edition=OL7353617M|work=OL45804W",
            page.records.single().remoteCoverRef
        )
        assertNull(mapOpenLibraryRecord(JSONObject("""{"key":"/books/OL1M","title":"Edition"}""")))
    }

    @Test
    fun capabilityIsLimitedToPublicCredentialFreeProviders() {
        assertEquals(true, RemoteSearchProviders.supports(MediaType.MANGA))
        assertEquals(true, RemoteSearchProviders.supports(MediaType.BOOK))
        assertFalse(RemoteSearchProviders.supports(MediaType.ANIME))
        assertFalse(RemoteSearchProviders.supports(MediaType.MOVIE))
        assertFalse(RemoteSearchProviders.supports(MediaType.SERIES))
    }

    private companion object {
        const val MANGA_RESPONSE = """
            {"data":[{"id":"32d76d19-8a05-4db0-9fc2-e0b0648fe9d0","attributes":{"title":{"en":"Solo Leveling"},"altTitles":[{"ko":"나 혼자만 레벨업"}],"year":2018,"originalLanguage":"ko","status":"completed","publicationDemographic":"shounen","tags":[]},"relationships":[{"type":"cover_art","attributes":{"fileName":"cover.jpg"}}]}],"total":1}
        """
        const val BOOK_RESPONSE = """
            {"docs":[{"key":"/works/OL45804W","title":"Fantastic Mr. Fox","first_publish_year":1970,"author_name":["Roald Dahl"],"subject":["Foxes"],"cover_i":123,"isbn":["978-0-14-032872-1","0140328726"],"edition_key":["OL7353617M"]}],"numFound":1}
        """
    }
}
