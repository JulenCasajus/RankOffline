package org.rankoffline.app

/**
 * Legal information about the RankOffline APPLICATION, not about downloaded packs.
 * Its own source, licence and trademark policy belong to the APK.
 * Notices for bundled libraries/assets must also remain available independently of packs.
 */
object RankOfflineLegalInfo {
    const val SOURCE_CODE_URL = "https://github.com/JulenCasajus/RankOffline"
    const val TRADEMARKS_URL =
        "https://github.com/JulenCasajus/RankOffline/blob/main/TRADEMARKS.md"
    const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
    const val APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

    val trustedUrls: Set<String> = setOf(
        SOURCE_CODE_URL, TRADEMARKS_URL, GPL_URL, APACHE_URL
    )
}
