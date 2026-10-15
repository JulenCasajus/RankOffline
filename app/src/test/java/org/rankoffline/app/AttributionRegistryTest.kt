package org.rankoffline.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributionRegistryTest {
    @Test
    fun tmdbRequiredNoticeIsExactAndNonTranslatableResourceMatches() {
        val expected = "This product uses the TMDB API but is not endorsed or certified by TMDB."
        assertEquals(expected, AttributionRegistry.TMDB_REQUIRED_NOTICE)

        val resource = sequenceOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml")
        ).first(File::isFile).readText()
        assertTrue(
            resource.contains(
                "<string name=\"about_tmdb_notice\" translatable=\"false\">$expected</string>"
            )
        )
    }

    @Test
    fun providerRegistryContainsEveryCurrentProvider() {
        assertEquals(
            setOf(
                "AnimeAPI",
                "AniList",
                "Jikan",
                "anime-offline-database",
                "MangaDex",
                "TMDB",
                "Open Library"
            ),
            AttributionRegistry.providers.map { it.name }.toSet()
        )
    }

    @Test
    fun everyTrustedExternalLinkUsesHttps() {
        assertTrue(AttributionRegistry.trustedUrls.isNotEmpty())
        assertTrue(AttributionRegistry.trustedUrls.all { it.startsWith("https://") })
    }

    @Test
    fun odblAndDbclAreScopedOnlyToAnimeDatabases() {
        val scoped = AttributionRegistry.dataLicences
            .filter { "ODbL-1.0" in it.licences || "DbCL-1.0" in it.licences }
            .map { it.dataSet }
            .toSet()
        assertEquals(
            setOf(
                "AnimeAPI compiled database",
                "anime-offline-database-derived alias database"
            ),
            scoped
        )
    }

    @Test
    fun requiredLocalLegalAssetsAreDeclared() {
        assertEquals(
            setOf(
                "legal/GPL-3.0.txt",
                "legal/Apache-2.0.txt",
                "legal/ODbL-1.0.txt",
                "legal/DbCL-1.0.txt",
                "legal/THIRD_PARTY_NOTICES.txt",
                "legal/TMDB_LOGO_PROVENANCE.txt"
            ),
            AttributionRegistry.requiredLegalAssets
        )
    }
}
