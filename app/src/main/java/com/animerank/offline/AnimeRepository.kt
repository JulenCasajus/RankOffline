package com.animerank.offline

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray

class AnimeRepository(
    private val context: Context,
    private val database: AnimeDatabase
) {
    private val dao = database.animeDao()

    fun observeRanking(limit: Int = 200): Flow<List<Anime>> =
        dao.observeRanking(limit).map { rows -> rows.map { it.toDomain() } }

    suspend fun search(
        query: String,
        sortOrder: CatalogSortOrder,
        limit: Int = 200
    ): List<Anime> = withContext(Dispatchers.IO) {
        dao.search(query.trim(), limit, sortOrder.name).map { it.toDomain() }
    }

    suspend fun animeCount(): Int = withContext(Dispatchers.IO) { dao.animeCount() }

    fun observeDownloadedCoverCount(): Flow<Int> = dao.observeDownloadedCoverCount()

    suspend fun loadRating(animeId: String): RatingDraft = withContext(Dispatchers.IO) {
        val rating = dao.rating(animeId) ?: return@withContext RatingDraft()
        val scores = dao.ratingScores(animeId).associate { entity ->
            entity.categoryId to entity.score.takeIf { entity.applicable }
        }
        RatingDraft(scores, rating.additiveScore, rating.notes, rating.status)
    }

    suspend fun saveRating(animeId: String, draft: RatingDraft, config: RatingSystemConfig) = withContext(Dispatchers.IO) {
        val result = ScoreCalculator.calculate(draft.categoryScores, draft.additiveScore, config)
        val rating = RatingEntity(
            animeId = animeId,
            additiveScore = draft.additiveScore.takeIf(Double::isFinite) ?: 0.0,
            qualityScore = result.qualityScore,
            finalScore = result.finalScore,
            notes = draft.notes,
            status = draft.status,
            updatedAt = System.currentTimeMillis()
        )
        val scores = draft.categoryScores.map { (categoryId, score) ->
            RatingScoreEntity(
                animeId = animeId,
                categoryId = categoryId,
                score = score?.takeIf(Double::isFinite),
                applicable = score?.isFinite() == true
            )
        }
        dao.saveRating(rating, scores)
    }

    suspend fun deleteRating(animeId: String) = withContext(Dispatchers.IO) {
        dao.deleteRating(animeId)
    }

    suspend fun recalculateAllRatings(config: RatingSystemConfig) = withContext(Dispatchers.IO) {
        dao.recalculateAll(config)
    }

    suspend fun updateCatalogIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        val assetVersion = context.assets.open(CATALOG_VERSION_ASSET).bufferedReader().use { it.readText().trim() }
        if (dao.metadata(CATALOG_VERSION_KEY) == assetVersion) return@withContext false

        val entries = context.assets.open(CATALOG_ASSET).bufferedReader().use { JSONArray(it.readText()) }
        database.runInTransaction {
            val db = database.openHelper.writableDatabase
            val assetRanks = mutableListOf<IncomingPopularityRank>()
            val insert = db.compileStatement(
                """
                INSERT OR IGNORE INTO anime(
                    id,title,mal_id,anilist_id,popularity,poster_url,remote_cover_url,
                    local_cover_path,cover_resolution_state
                ) VALUES(?,?,?,?,?,?,?,NULL,'UNKNOWN')
                """.trimIndent()
            )
            val update = db.compileStatement(
                """
                UPDATE anime SET title=?, mal_id=COALESCE(?,mal_id), anilist_id=COALESCE(?,anilist_id),
                    popularity=?, poster_url=COALESCE(?,poster_url),
                    remote_cover_url=COALESCE(remote_cover_url,?)
                WHERE id=?
                """.trimIndent()
            )
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                val id = item.getString("id")
                val title = item.getString("title")
                val malId = item.optLongOrNull("malId")
                val anilistId = item.optLongOrNull("anilistId")
                val popularity = item.optLongOrNull("popularity")
                val popularityRank = item.optLongOrNull("popularityRank")?.toInt()
                val posterUrl = item.optString("posterUrl", "").takeIf(String::isNotBlank)

                insert.clearBindings()
                insert.bindString(1, id)
                insert.bindString(2, title)
                insert.bindNullableLong(3, malId)
                insert.bindNullableLong(4, anilistId)
                insert.bindNullableLong(5, popularity)
                insert.bindNullableString(6, posterUrl)
                insert.bindNullableString(7, posterUrl)
                insert.executeInsert()

                update.clearBindings()
                update.bindString(1, title)
                update.bindNullableLong(2, malId)
                update.bindNullableLong(3, anilistId)
                update.bindNullableLong(4, popularity)
                update.bindNullableString(5, posterUrl)
                update.bindNullableString(6, posterUrl)
                update.bindString(7, id)
                update.executeUpdateDelete()
                popularityRank?.let { assetRanks += IncomingPopularityRank(id, it) }
            }
            // A catalog-only asset must not erase a ranking that the user refreshed in-app.
            // When the build pipeline includes a popularity snapshot it is always a complete
            // Top 5000, so a non-empty list is safe to reconcile as a full replacement.
            if (assetRanks.isNotEmpty()) {
                applyRankPlan(db, assetRanks, PopularityRankReconciler.FULL_RANKING_LIMIT)
            }
            db.execSQL(
                "UPDATE anime SET cover_resolution_state='AVAILABLE' " +
                    "WHERE remote_cover_url IS NOT NULL AND cover_resolution_state='UNKNOWN'"
            )
            db.execSQL(
                "INSERT OR REPLACE INTO catalog_metadata(metadata_key,metadata_value) VALUES(?,?)",
                arrayOf(CATALOG_VERSION_KEY, assetVersion)
            )
        }
        true
    }

    suspend fun animeIdsByAnilistId(id: Long): List<String> = withContext(Dispatchers.IO) {
        dao.animeIdsByAnilistId(id)
    }

    suspend fun animeIdsByMalId(id: Long): List<String> = withContext(Dispatchers.IO) {
        dao.animeIdsByMalId(id)
    }

    suspend fun animeByExternalIds(anilistIds: List<Long>, malIds: List<Long>): List<Anime> =
        withContext(Dispatchers.IO) {
            val byId = linkedMapOf<String, AnimeEntity>()
            anilistIds.distinct().chunked(SQLITE_BIND_CHUNK).forEach { ids ->
                dao.animeByAnilistIds(ids).forEach { byId[it.id] = it }
            }
            malIds.distinct().chunked(SQLITE_BIND_CHUNK).forEach { ids ->
                dao.animeByMalIds(ids).forEach { byId.putIfAbsent(it.id, it) }
            }
            byId.values.map { it.toDomain() }
        }

    suspend fun updatePopularityRanking(entries: List<PopularAnimeEntry>, limit: Int): Int =
        withContext(Dispatchers.IO) {
        validatePopularAnimeResponse(entries, limit)
        var matchedRows = 0
        database.runInTransaction {
            val db = database.openHelper.writableDatabase
            val resolution = resolvePopularAnime(db, entries)
            val resolved = resolution.entries
            Log.d(
                TAG,
                "Popularity reconciliation requestedN=$limit received=${entries.size} " +
                    "matched=${resolved.size} unmatched=${entries.size - resolved.size} " +
                    "duplicateMatchesDiscarded=${resolution.duplicateMatchesDiscarded} " +
                    "finalBeforeReconcile=${resolved.size}"
            )
            val statement = db.compileStatement("UPDATE anime SET popularity = ? WHERE id = ?")
            resolved.forEach { match ->
                statement.clearBindings()
                match.entry.popularity?.let { statement.bindLong(1, it.toLong()) } ?: statement.bindNull(1)
                statement.bindString(2, match.animeId)
                matchedRows += statement.executeUpdateDelete()
            }
            applyRankPlan(
                db,
                resolved.map { IncomingPopularityRank(it.animeId, it.sourceRank) },
                limit
            )
        }
        matchedRows
    }

    suspend fun updateRemoteCover(animeId: String, url: String?, state: CoverResolutionState) = withContext(Dispatchers.IO) {
        dao.updateRemoteCover(animeId, url, state.name)
    }

    suspend fun updateLocalCover(animeId: String, path: String?, state: CoverResolutionState) = withContext(Dispatchers.IO) {
        dao.updateLocalCover(animeId, path, state.name)
    }

    suspend fun clearLocalCoverPath(animeId: String) = withContext(Dispatchers.IO) {
        dao.clearLocalCoverPath(animeId)
    }

    suspend fun updateCoverResolutionState(animeId: String, state: CoverResolutionState) = withContext(Dispatchers.IO) {
        dao.updateCoverResolutionState(animeId, state.name)
    }

    fun observeLocalCoverPath(animeId: String): Flow<String?> = dao.observeLocalCoverPath(animeId)

    suspend fun allLocalCoverPaths(): List<String> = withContext(Dispatchers.IO) {
        dao.allLocalCoverPaths()
    }

    suspend fun clearAllLocalCoverPaths() = withContext(Dispatchers.IO) {
        dao.clearAllLocalCoverPaths()
    }

    private fun AnimeRow.toDomain() = Anime(
        id = id,
        title = title,
        malId = malId,
        anilistId = anilistId,
        popularity = popularity,
        popularityRank = popularityRank,
        finalScore = finalScore,
        baseScore = qualityScore,
        additiveScore = additiveScore,
        status = status,
        remoteCoverUrl = remoteCoverUrl,
        localCoverPath = localCoverPath,
        coverResolutionState = runCatching { CoverResolutionState.valueOf(coverResolutionState) }
            .getOrDefault(CoverResolutionState.UNKNOWN)
    )

    private fun AnimeEntity.toDomain() = Anime(
        id = id,
        title = title,
        malId = malId,
        anilistId = anilistId,
        popularity = popularity,
        popularityRank = popularityRank,
        remoteCoverUrl = remoteCoverUrl ?: posterUrl,
        localCoverPath = localCoverPath,
        coverResolutionState = runCatching { CoverResolutionState.valueOf(coverResolutionState) }
            .getOrDefault(CoverResolutionState.UNKNOWN)
    )

    private fun org.json.JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun SupportSQLiteStatement.bindNullableLong(index: Int, value: Long?) {
        if (value == null) bindNull(index) else bindLong(index, value)
    }

    private fun SupportSQLiteStatement.bindNullableString(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindString(index, value)
    }

    private fun resolvePopularAnime(
        db: SupportSQLiteDatabase,
        entries: List<PopularAnimeEntry>
    ): PopularAnimeResolution {
        val catalog = buildList {
            db.query("SELECT id,anilist_id,mal_id FROM anime").use { cursor ->
                while (cursor.moveToNext()) {
                    add(
                        CatalogExternalIds(
                            animeId = cursor.getString(0),
                            anilistId = if (cursor.isNull(1)) null else cursor.getLong(1),
                            malId = if (cursor.isNull(2)) null else cursor.getLong(2)
                        )
                    )
                }
            }
        }
        val byAnilist = catalog.filter { it.anilistId != null }.groupBy { it.anilistId }
        val byMal = catalog.filter { it.malId != null }.groupBy { it.malId }
        val incomingMalCounts = entries.mapNotNull { it.malId }.groupingBy { it }.eachCount()
        val matchedAnimeIds = mutableSetOf<String>()
        var duplicateMatchesDiscarded = 0
        val resolved = buildList {
            entries.forEachIndexed { index, entry ->
                val anilistMatches = byAnilist[entry.anilistId].orEmpty()
                val malId = entry.malId
                val match = if (anilistMatches.size == 1) {
                    anilistMatches.single()
                } else {
                    val malMatches = malId?.let { byMal[it] }.orEmpty()
                    malMatches.singleOrNull()?.takeIf { candidate ->
                        malId != null && incomingMalCounts[malId] == 1 &&
                            candidate.anilistId in listOf(null, entry.anilistId)
                    }
                }
                if (match != null && matchedAnimeIds.add(match.animeId)) {
                    add(ResolvedPopularAnime(match.animeId, index + 1, entry))
                } else if (match != null) {
                    duplicateMatchesDiscarded++
                }
            }
        }
        return PopularAnimeResolution(resolved, duplicateMatchesDiscarded)
    }

    private fun applyRankPlan(
        db: SupportSQLiteDatabase,
        incoming: List<IncomingPopularityRank>,
        limit: Int
    ) {
        val previous = buildList {
            db.query(
                "SELECT id,popularity_rank FROM anime " +
                    "WHERE popularity_rank IS NOT NULL ORDER BY popularity_rank"
            ).use { cursor ->
                while (cursor.moveToNext()) add(LocalPopularityRank(cursor.getString(0), cursor.getInt(1)))
            }
        }
        val plan = PopularityRankReconciler.reconcile(previous, incoming, limit)
        if (plan.changedRanks.isEmpty()) return
        val statement = db.compileStatement("UPDATE anime SET popularity_rank = ? WHERE id = ?")
        plan.changedRanks.keys.forEachIndexed { index, animeId ->
            statement.clearBindings()
            statement.bindLong(1, -(index + 1L))
            statement.bindString(2, animeId)
            check(statement.executeUpdateDelete() == 1)
        }
        plan.changedRanks.forEach { (animeId, rank) ->
            statement.clearBindings()
            statement.bindLong(1, rank.toLong())
            statement.bindString(2, animeId)
            check(statement.executeUpdateDelete() == 1)
        }
    }

    private companion object {
        const val CATALOG_ASSET = "anime_catalog.json"
        const val CATALOG_VERSION_ASSET = "anime_catalog.version"
        const val CATALOG_VERSION_KEY = "catalog_version"
        const val SQLITE_BIND_CHUNK = 500
        const val TAG = "AnimeRepository"
    }

    private data class CatalogExternalIds(
        val animeId: String,
        val anilistId: Long?,
        val malId: Long?
    )

    private data class ResolvedPopularAnime(
        val animeId: String,
        val sourceRank: Int,
        val entry: PopularAnimeEntry
    )

    private data class PopularAnimeResolution(
        val entries: List<ResolvedPopularAnime>,
        val duplicateMatchesDiscarded: Int
    )
}
