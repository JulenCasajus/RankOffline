package org.rankoffline.app

data class ProviderAttribution(
    val name: String,
    val url: String
)

data class DataLicenceAttribution(
    val dataSet: String,
    val licences: Set<String>
)

/** Static, reviewed destinations only. Catalog/provider payloads never supply clickable URLs. */
object AttributionRegistry {
    const val TMDB_REQUIRED_NOTICE =
        "This product uses the TMDB API but is not endorsed or certified by TMDB."

    const val SOURCE_CODE_URL = "https://github.com/JulenCasajus/RankOffline"
    const val TRADEMARKS_URL = "https://github.com/JulenCasajus/RankOffline/blob/main/TRADEMARKS.md"
    const val ANIME_API_URL = "https://github.com/nattadasu/animeApi"
    const val ANILIST_URL = "https://anilist.co"
    const val JIKAN_URL = "https://jikan.moe"
    const val ANIME_OFFLINE_DATABASE_URL = "https://github.com/cedya77/anime-offline-database"
    const val ANIME_OFFLINE_DATABASE_ORIGINAL_URL =
        "https://github.com/manami-project/anime-offline-database"
    const val MANGADEX_URL = "https://mangadex.org"
    const val TMDB_URL = "https://www.themoviedb.org"
    const val OPEN_LIBRARY_URL = "https://openlibrary.org"
    const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
    const val APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0"
    const val ODBL_URL = "https://opendatacommons.org/licenses/odbl/1-0/"
    const val DBCL_URL = "https://opendatacommons.org/licenses/dbcl/1-0/"
    const val CC0_URL = "https://creativecommons.org/publicdomain/zero/1.0/"

    val providers = listOf(
        ProviderAttribution("AnimeAPI", ANIME_API_URL),
        ProviderAttribution("AniList", ANILIST_URL),
        ProviderAttribution("Jikan", JIKAN_URL),
        ProviderAttribution("anime-offline-database", ANIME_OFFLINE_DATABASE_URL),
        ProviderAttribution("MangaDex", MANGADEX_URL),
        ProviderAttribution("TMDB", TMDB_URL),
        ProviderAttribution("Open Library", OPEN_LIBRARY_URL)
    )

    val dataLicences = listOf(
        DataLicenceAttribution("AnimeAPI compiled database", setOf("ODbL-1.0", "DbCL-1.0")),
        DataLicenceAttribution(
            "anime-offline-database-derived alias database",
            setOf("ODbL-1.0", "DbCL-1.0")
        ),
        DataLicenceAttribution("Some AnimeAPI raw source components", setOf("CC0-1.0"))
    )

    val requiredLegalAssets = setOf(
        "legal/GPL-3.0.txt",
        "legal/Apache-2.0.txt",
        "legal/ODbL-1.0.txt",
        "legal/DbCL-1.0.txt",
        "legal/THIRD_PARTY_NOTICES.txt",
        "legal/TMDB_LOGO_PROVENANCE.txt"
    )

    val trustedUrls = setOf(
        SOURCE_CODE_URL,
        TRADEMARKS_URL,
        GPL_URL,
        APACHE_URL,
        ODBL_URL,
        DBCL_URL,
        CC0_URL,
        ANIME_OFFLINE_DATABASE_ORIGINAL_URL
    ) + providers.map { it.url }
}
