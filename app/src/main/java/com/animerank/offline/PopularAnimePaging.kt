package com.animerank.offline

internal suspend fun fetchCompletePopularAnime(
    count: Int,
    pageSize: Int,
    fetchPage: suspend (page: Int, perPage: Int) -> List<PopularAnimeEntry>,
    onPageFetched: (page: Int, requested: Int, received: Int, total: Int) -> Unit = { _, _, _, _ -> },
    afterPage: suspend (page: Int, pages: Int) -> Unit = { _, _ -> }
): List<PopularAnimeEntry> {
    require(count > 0)
    require(pageSize > 0)
    val result = mutableListOf<PopularAnimeEntry>()
    val pages = (count + pageSize - 1) / pageSize
    for (page in 1..pages) {
        val requested = minOf(pageSize, count - result.size)
        if (requested <= 0) break
        val received = fetchPage(page, requested)
        require(received.size <= requested) {
            "AniList page $page returned ${received.size} results for a request of $requested"
        }
        result += received
        onPageFetched(page, requested, received.size, result.size)
        if (page < pages) afterPage(page, pages)
    }
    validatePopularAnimeResponse(result, count)
    return result
}
