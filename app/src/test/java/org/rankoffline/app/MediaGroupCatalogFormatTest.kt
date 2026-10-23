package org.rankoffline.app

import org.junit.Assert.assertThrows
import org.junit.Test

class MediaGroupCatalogFormatTest {
    private val manifest = MediaGroupCatalogManifest(
        formatVersion = MEDIA_GROUP_CATALOG_FORMAT_VERSION,
        mediaType = "series",
        provider = "tmdb",
        catalogVersion = "snapshot",
        sha256 = "0".repeat(64),
        groupCount = 1
    )

    @Test
    fun requiresPublicationTimeRepresentativeAndMemberCount() {
        val record = MediaGroupCatalogRecord(
            id = "series:tmdb:7",
            mediaType = "series",
            groupType = "SEASONS",
            provider = "tmdb",
            providerGroupId = "7",
            displayTitle = "Show",
            representativeMediaId = "series:tmdb:7:season:1",
            memberCount = 2,
            popularity = 90.0,
            popularityRank = 4,
            members = listOf(
                MediaGroupCatalogMember("series:tmdb:7:season:1", 1),
                MediaGroupCatalogMember("series:tmdb:7:season:2", 2)
            )
        )

        validateMediaGroupCatalogRecord(record, manifest)
        assertThrows(IllegalArgumentException::class.java) {
            validateMediaGroupCatalogRecord(record.copy(memberCount = 3), manifest)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateMediaGroupCatalogRecord(
                record.copy(representativeMediaId = "series:tmdb:7:season:99"),
                manifest
            )
        }
    }
}
