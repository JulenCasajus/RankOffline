package org.rankoffline.app

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class CoverPreloadManager(
    private val animeRepository: AnimeRepository,
    private val coverRepository: CoverRepository
) {
    private val _progress = MutableStateFlow(CoverPreloadProgress())
    val progress: StateFlow<CoverPreloadProgress> = _progress.asStateFlow()

    suspend fun preload(count: Int) = withContext(Dispatchers.IO) {
        _progress.value = TopUpdateProgress.start(count)
        try {
            val popular = fetchPopularAnime(count) { fetched ->
                _progress.value = TopUpdateProgress.popularityFetched(_progress.value, fetched)
            }
            _progress.value = TopUpdateProgress.applyingPopularity(_progress.value)
            animeRepository.updatePopularityRanking(popular, count)
            _progress.value = TopUpdateProgress.downloadingCovers(
                progress = _progress.value,
                processed = 0,
                existing = 0,
                errors = 0
            )
            val local = animeRepository.animeByExternalIds(
                anilistIds = popular.map { it.anilistId },
                malIds = popular.mapNotNull { it.malId }
            )
            val plan = PopularCoverPlanner.plan(popular, local, count)
            val unavailableFromSource = (count - popular.size).coerceAtLeast(0)
            var existing = plan.existing
            var downloaded = 0
            var errors = plan.unmatched + unavailableFromSource
            var processed = existing + errors
            _progress.value = TopUpdateProgress.downloadingCovers(
                progress = _progress.value,
                processed = processed,
                existing = existing,
                errors = errors
            )

            plan.pending.chunked(WORKER_COUNT).forEach { batch ->
                val results = coroutineScope {
                    batch.map { candidate ->
                        async { coverRepository.downloadPreselectedCover(candidate.anime, candidate.coverUrl) }
                    }.awaitAll()
                }
                results.forEach { result ->
                    when (result) {
                        CoverDownloadResult.Downloaded -> downloaded++
                        CoverDownloadResult.AlreadyAvailable,
                        CoverDownloadResult.AlreadyInProgress -> existing++
                        CoverDownloadResult.NetworkError,
                        CoverDownloadResult.NotFound,
                        CoverDownloadResult.TemporaryError -> errors++
                    }
                    processed++
                }
                _progress.value = CoverPreloadProgress(
                    status = CoverPreloadStatus.DOWNLOADING_COVERS,
                    target = count,
                    popularityProcessed = count,
                    processed = processed.coerceAtMost(count),
                    existing = existing,
                    downloaded = downloaded,
                    errors = errors,
                    popularityUpdated = true
                )
            }
            _progress.value = TopUpdateProgress.completed(_progress.value).copy(
                message = UserMessage.Text(R.string.message_top_updated, listOf(count))
            )
        } catch (exception: CancellationException) {
            _progress.value = CoverPreloadProgress()
            throw exception
        } catch (exception: Exception) {
            Log.w(TAG, "Popular cover preload failed", exception)
            _progress.value = _progress.value.copy(
                status = CoverPreloadStatus.FAILED,
                message = UserMessage.Text(R.string.message_top_update_failed, listOf(count))
            )
        }
    }

    fun resetProgress() {
        _progress.value = CoverPreloadProgress()
    }

    private suspend fun fetchPopularAnime(
        count: Int,
        onProgress: (Int) -> Unit
    ): List<PopularAnimeEntry> {
        val pages = (count + PAGE_SIZE - 1) / PAGE_SIZE
        Log.d(TAG, "Top update requestedN=$count pagesRequested=$pages pageSize=$PAGE_SIZE")
        val result = fetchCompletePopularAnime(
            count = count,
            pageSize = PAGE_SIZE,
            fetchPage = ::fetchPageWithRetry,
            onPageFetched = { page, requested, received, total ->
                Log.d(
                    TAG,
                    "Top update requestedN=$count page=$page requested=$requested " +
                        "received=$received total=$total"
                )
                onProgress(total.coerceAtMost(count))
            },
            afterPage = { page, _ ->
                if (count > 1_000 && page < pages) delay(PAGE_REQUEST_DELAY_MS)
            }
        )
        Log.d(
            TAG,
            "Top update requestedN=$count total=${result.size} validIds=${result.count { it.anilistId > 0 }} " +
                "duplicateIds=${result.size - result.distinctBy { it.anilistId }.size}"
        )
        return result
    }

    private suspend fun fetchPageWithRetry(page: Int, perPage: Int): List<PopularAnimeEntry> {
        var lastError: IOException? = null
        repeat(MAX_PAGE_ATTEMPTS) { attempt ->
            try {
                return fetchPage(page, perPage)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: AniListHttpException) {
                val isTemporary = exception.statusCode == 429 || exception.statusCode >= 500
                Log.w(
                    TAG,
                    "AniList page=$page attempt=${attempt + 1}/$MAX_PAGE_ATTEMPTS " +
                        "http=${exception.statusCode} temporary=$isTemporary"
                )
                if (!isTemporary) throw exception
                lastError = exception
                if (attempt + 1 < MAX_PAGE_ATTEMPTS) {
                    delay(
                        exception.retryAfterMillis
                            ?.coerceIn(MIN_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS)
                            ?: (RETRY_DELAY_MS * (attempt + 1))
                    )
                }
            } catch (exception: IOException) {
                Log.w(
                    TAG,
                    "AniList page=$page attempt=${attempt + 1}/$MAX_PAGE_ATTEMPTS " +
                        "error=${exception.javaClass.simpleName}: ${exception.message}"
                )
                lastError = exception
                if (attempt + 1 < MAX_PAGE_ATTEMPTS) delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw lastError ?: IOException("AniList request failed")
    }

    private fun fetchPage(page: Int, perPage: Int): List<PopularAnimeEntry> {
        val query = """
            query (${'$'}page: Int!, ${'$'}perPage: Int!) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: ANIME, sort: POPULARITY_DESC) {
                  id
                  idMal
                  popularity
                  coverImage { extraLarge large medium }
                }
              }
            }
        """.trimIndent()
        val body = JSONObject()
            .put("query", query)
            .put("variables", JSONObject().put("page", page).put("perPage", perPage))
        val connection = openConnection()
        return try {
            connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val retryAfterMillis = connection.getHeaderField("Retry-After")
                    ?.trim()
                    ?.toLongOrNull()
                    ?.times(1_000L)
                throw AniListHttpException(code, retryAfterMillis)
            }
            val root = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            if (root.optJSONArray("errors") != null) {
                throw IllegalArgumentException("AniList returned GraphQL errors on page $page")
            }
            val media = root.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
                ?: throw IllegalArgumentException("AniList returned no media array on page $page")
            buildList {
                for (index in 0 until media.length()) {
                    val item = media.getJSONObject(index)
                    val cover = item.optJSONObject("coverImage")
                    add(
                        PopularAnimeEntry(
                            anilistId = item.getLong("id"),
                            malId = item.optLongOrNull("idMal"),
                            popularity = item.optIntOrNull("popularity"),
                            coverUrl = cover?.optString("extraLarge")?.takeIf(String::isNotBlank)
                                ?: cover?.optString("large")?.takeIf(String::isNotBlank)
                                ?: cover?.optString("medium")?.takeIf(String::isNotBlank)
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(): HttpURLConnection =
        (URL(ANILIST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RankOffline/1.0")
        }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    private companion object {
        const val TAG = "CoverPreloadManager"
        const val ANILIST_URL = "https://graphql.anilist.co"
        const val PAGE_SIZE = 50
        const val WORKER_COUNT = 4
        const val MAX_PAGE_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 750L
        const val MIN_RETRY_DELAY_MS = 250L
        const val MAX_RETRY_DELAY_MS = 10_000L
        const val PAGE_REQUEST_DELAY_MS = 750L
    }

    private class AniListHttpException(
        val statusCode: Int,
        val retryAfterMillis: Long?
    ) : IOException("AniList HTTP $statusCode")
}
