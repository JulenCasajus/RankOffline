package org.rankoffline.app

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
    private val database: AnimeDatabase,
    private val catalogWorkingDirectory: java.io.File = context.cacheDir
) {
    private val dao = database.animeDao()

    fun observeRanking(limit: Int): Flow<List<Anime>> =
        observeRanking(
            filters = AnimeFilters(),
            sortOrder = RankingSortOrder.FINAL_SCORE,
            categoryId = null,
            limit = limit
        )

    fun observeRanking(
        filters: AnimeFilters = AnimeFilters(),
        sortOrder: RankingSortOrder = RankingSortOrder.FINAL_SCORE,
        categoryId: String? = null,
        limit: Int = 200,
        descending: Boolean = true,
        mergeSeasonsEnabled: Boolean = false
    ): Flow<List<Anime>> = (if (!mergeSeasonsEnabled) dao.observeRanking(
        limit = limit,
        sortOrder = sortOrder.name,
        descending = descending,
        categoryId = categoryId.orEmpty(),
        genre = filters.genre,
        format = filters.format,
        year = filters.year,
        studio = filters.studio
    ) else dao.observeGroupedRanking(GroupedQueries.ranking(sortOrder, categoryId, filters, limit, descending)))
        .map { rows -> rows.map { it.toDomain() } }

    suspend fun search(
        query: String,
        sortOrder: CatalogSortOrder,
        filters: AnimeFilters = AnimeFilters(),
        limit: Int = 200,
        offset: Int = 0,
        mergeSeasonsEnabled: Boolean = false
    ): List<Anime> = withContext(Dispatchers.IO) {
        (if (!mergeSeasonsEnabled) dao.search(
            query = query.trim(),
            limit = limit,
            offset = offset,
            sortOrder = sortOrder.name,
            genre = filters.genre,
            format = filters.format,
            year = filters.year,
            studio = filters.studio
        ) else dao.groupedSearch(GroupedQueries.catalog(query.trim(), sortOrder, filters, limit, offset)))
            .map { it.toDomain() }
    }

    suspend fun groupDetail(groupId: String): GroupDetail? = withContext(Dispatchers.IO) {
        val group = dao.group(groupId) ?: return@withContext null
        val members = dao.groupMembers(groupId).map { it.toDomain() }
        val derived = deriveGroupRating(dao.ratedGroupMembers(groupId), dao.ratedGroupMemberScores(groupId))
        GroupDetail(
            group,
            members,
            dao.groupRating(groupId)?.takeIf { it.finalScore?.isFinite() == true },
            derived
        )
    }

    suspend fun loadGroupRating(groupId: String, fromMembers: Boolean): RatingDraft = withContext(Dispatchers.IO) {
        val rating = dao.groupRating(groupId)?.takeIf { it.finalScore?.isFinite() == true }
        if (rating == null) {
            return@withContext if (fromMembers) {
                deriveGroupRating(dao.ratedGroupMembers(groupId), dao.ratedGroupMemberScores(groupId)).asDraft()
            } else RatingDraft()
        }
        RatingDraft(
            categoryScores = dao.groupRatingScores(groupId).associate { it.categoryId to it.score.takeIf { _ -> it.applicable } },
            additiveScore = rating.additiveScore, notes = rating.notes, status = rating.status,
            finalScore = rating.finalScore, qualityScore = rating.qualityScore, profileId = rating.profileId
        )
    }

    suspend fun saveElaborateGroupRating(
        groupId: String, draft: RatingDraft, config: RatingSystemConfig,
        finalScoreOverride: Double?, profileId: String
    ) = withContext(Dispatchers.IO) {
        require(draft.categoryScores.values.all { it == null || isDiscreteRatingValue(it) })
        val bonus = requireNotNull(snapBonusValue(draft.additiveScore))
        val calculated = ScoreCalculator.calculate(draft.categoryScores, bonus, config)
        val final = finalScoreOverride?.let { requireNotNull(normalizeDirectRatingValue(it)) } ?: calculated.finalScore
        dao.saveGroupRating(
            GroupRatingEntity(groupId, bonus, calculated.qualityScore, final, draft.notes, draft.status, System.currentTimeMillis(), profileId),
            draft.categoryScores.map { (categoryId, score) ->
                GroupRatingScoreEntity(groupId, categoryId, score?.takeIf(Double::isFinite), score?.isFinite() == true)
            }
        )
    }

    suspend fun saveSimpleGroupRating(groupId: String, draft: RatingDraft, qualityScore: Double, finalScore: Double) =
        withContext(Dispatchers.IO) {
            dao.upsertGroupRating(GroupRatingEntity(
                groupId, requireNotNull(snapBonusValue(draft.additiveScore)),
                requireNotNull(normalizeDirectRatingValue(qualityScore)),
                requireNotNull(normalizeDirectRatingValue(finalScore)),
                draft.notes, draft.status, System.currentTimeMillis(), draft.profileId
            ))
        }

    suspend fun deleteGroupRating(groupId: String) = withContext(Dispatchers.IO) { dao.deleteGroupRating(groupId) }

    suspend fun filterOptions(): AnimeFilterOptions = withContext(Dispatchers.IO) {
        AnimeFilterOptions(
            genres = dao.genres(),
            formats = normalizedFormatOptions(dao.formats()),
            years = dao.years(),
            studios = dao.studios()
        )
    }

    suspend fun animeCount(): Int = withContext(Dispatchers.IO) { dao.animeCount() }

    suspend fun verifyPrecomputedGroups() = withContext(Dispatchers.IO) {
        val groups = dao.catalogGroupCount()
        val memberships = dao.catalogGroupMembershipCount()
        val invalid = dao.invalidCatalogGroupCount()
        require(groups > 0) { "Anime pack declares merge support but contains no usable groups" }
        require(invalid == 0) { "Anime pack contains $invalid groups with fewer than two members" }
        if (BuildConfig.DEBUG) android.util.Log.d(
            "RankOfflineMerge",
            "pack=${OfficialPackIds.Anime.value} operation=group-verify groups=$groups " +
                "memberships=$memberships invalid=$invalid"
        )
    }

    suspend fun deleteCatalogReferenceData() = withContext(Dispatchers.IO) {
        dao.deleteCatalogReferenceData()
    }

    suspend fun deleteAllPersonalData() = withContext(Dispatchers.IO) {
        dao.deleteAllPersonalData()
    }

    suspend fun purgePackData() = withContext(Dispatchers.IO) {
        dao.purgePackData()
    }

    suspend fun installPackCovers(manifest: PackManifest, directory: java.io.File) = withContext(Dispatchers.IO) {
        manifest.readCoverIndex(directory).forEach { cover ->
            dao.updateLocalCover(
                cover.canonicalId,
                java.io.File(directory, cover.relativePath).absolutePath,
                CoverResolutionState.AVAILABLE.name
            )
        }
    }

    fun observeDownloadedCoverCount(): Flow<Int> = dao.observeDownloadedCoverCount()

    suspend fun ratedCoverAnime(): List<Anime> = withContext(Dispatchers.IO) {
        dao.ratedCoverAnime().map { it.toDomain() }
    }

    suspend fun topPopularCoverAnime(limit: Int): List<Anime> = withContext(Dispatchers.IO) {
        dao.topPopularCoverAnime(limit).map { it.toDomain() }
    }

    suspend fun loadRating(animeId: String): RatingDraft = withContext(Dispatchers.IO) {
        val rating = dao.rating(animeId) ?: return@withContext RatingDraft()
        val scores = dao.ratingScores(animeId).associate { entity ->
            entity.categoryId to entity.score.takeIf { entity.applicable }
        }
        RatingDraft(
            categoryScores = scores,
            additiveScore = rating.additiveScore,
            notes = rating.notes,
            status = rating.status,
            finalScore = rating.finalScore,
            qualityScore = rating.qualityScore,
            profileId = rating.profileId
        )
    }

    suspend fun saveElaborateRating(
        animeId: String,
        draft: RatingDraft,
        config: RatingSystemConfig,
        finalScoreOverride: Double? = null,
        profileId: String? = null
    ) = withContext(Dispatchers.IO) {
        require(draft.categoryScores.values.all { it == null || isDiscreteRatingValue(it) })
        val normalizedBonus = requireNotNull(snapBonusValue(draft.additiveScore))
        val result = ScoreCalculator.calculate(draft.categoryScores, normalizedBonus, config)
        val finalScore = finalScoreOverride?.let { requireNotNull(normalizeDirectRatingValue(it)) }
            ?: result.finalScore
        val rating = RatingEntity(
            animeId = animeId,
            additiveScore = normalizedBonus,
            qualityScore = result.qualityScore,
            finalScore = finalScore,
            notes = draft.notes,
            status = draft.status,
            updatedAt = System.currentTimeMillis(),
            profileId = profileId
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

    suspend fun saveSimpleRating(
        animeId: String,
        draft: RatingDraft,
        qualityScore: Double,
        finalScore: Double
    ) = withContext(Dispatchers.IO) {
        val normalizedQuality = requireNotNull(normalizeDirectRatingValue(qualityScore))
        val normalizedBonus = requireNotNull(snapBonusValue(draft.additiveScore))
        val normalizedFinal = requireNotNull(normalizeDirectRatingValue(finalScore))
        dao.upsertRating(
            RatingEntity(
                animeId = animeId,
                additiveScore = normalizedBonus,
                qualityScore = normalizedQuality,
                finalScore = normalizedFinal,
                notes = draft.notes,
                status = draft.status,
                updatedAt = System.currentTimeMillis(),
                profileId = draft.profileId
            )
        )
    }

    suspend fun deleteRating(animeId: String) = withContext(Dispatchers.IO) {
        dao.deleteRating(animeId)
    }

    suspend fun deleteRatingCategory(categoryId: String) = withContext(Dispatchers.IO) {
        dao.deleteRatingScoresForCategory(categoryId)
        dao.deleteGroupRatingScoresForCategory(categoryId)
    }

    suspend fun deleteRatingScoresOutsideCategories(categoryIds: List<String>) = withContext(Dispatchers.IO) {
        if (categoryIds.isEmpty()) {
            dao.deleteAllRatingScores()
            dao.deleteAllGroupRatingScores()
        } else {
            dao.deleteRatingScoresOutsideCategories(categoryIds)
            dao.deleteGroupRatingScoresOutsideCategories(categoryIds)
        }
    }

    suspend fun updateCatalogIfNeeded(catalogDirectory: java.io.File): Boolean = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val versionFile = java.io.File(catalogDirectory, CATALOG_VERSION_ASSET)
        val catalogFile = java.io.File(catalogDirectory, CATALOG_ASSET)
        require(versionFile.isFile && catalogFile.isFile) { "Incomplete Anime category pack" }
        val assetVersion = versionFile.bufferedReader().use { it.readText().trim() }
        if (dao.metadata(CATALOG_VERSION_KEY) == assetVersion) return@withContext false

        val parseStarted = System.nanoTime()
        val entries = catalogFile.bufferedReader().use { JSONArray(it.readText()) }
        val parseMillis = (System.nanoTime() - parseStarted) / 1_000_000L
        val freshCatalog = dao.animeCount() == 0
        val sqlStarted = System.nanoTime()
        database.runInTransaction {
            val db = database.openHelper.writableDatabase
            val assetRanks = mutableListOf<IncomingPopularityRank>()
            db.execSQL("DELETE FROM anime_genre")
            db.execSQL("DELETE FROM anime_studio")
            val insert = db.compileStatement(
                """
                INSERT OR IGNORE INTO anime(
                    id,title,mal_id,anilist_id,popularity,poster_url,remote_cover_url,
                    local_cover_path,cover_resolution_state,format,season_year,media_status,
                    source,episodes,country_of_origin,average_score,start_date
                ) VALUES(?,?,?,?,?,?,?,NULL,'UNKNOWN',?,?,?,?,?,?,?,?)
                """.trimIndent()
            )
            val update = db.compileStatement(
                """
                UPDATE anime SET title=?, mal_id=COALESCE(?,mal_id), anilist_id=COALESCE(?,anilist_id),
                    popularity=?, poster_url=COALESCE(?,poster_url),
                    remote_cover_url=COALESCE(remote_cover_url,?), format=?, season_year=?,
                    media_status=?, source=?, episodes=?, country_of_origin=?, average_score=?, start_date=?
                WHERE id=?
                """.trimIndent()
            )
            val insertGenre = db.compileStatement("INSERT OR IGNORE INTO anime_genre(anime_id,genre) VALUES(?,?)")
            val insertStudio = db.compileStatement("INSERT OR IGNORE INTO anime_studio(anime_id,studio) VALUES(?,?)")
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                val id = item.getString("id")
                val title = item.getString("title")
                val malId = item.optLongOrNull("malId")
                val anilistId = item.optLongOrNull("anilistId")
                val popularity = item.optLongOrNull("popularity")
                val popularityRank = item.optLongOrNull("popularityRank")?.toInt()
                val posterUrl = item.optString("posterUrl", "").takeIf(String::isNotBlank)
                val format = item.optNullableString("format")
                val seasonYear = item.optLongOrNull("seasonYear")
                val mediaStatus = item.optNullableString("status")
                val source = item.optNullableString("source")
                val episodes = item.optLongOrNull("episodes")
                val country = item.optNullableString("countryOfOrigin")
                val averageScore = item.optLongOrNull("averageScore")
                val startDate = item.optJSONObject("startDate")?.let { date ->
                    val year = date.optInt("year")
                    val month = date.optInt("month")
                    val day = date.optInt("day")
                    if (year in 1..9999 && month in 1..12 && day in 1..31)
                        "%04d-%02d-%02d".format(java.util.Locale.ROOT, year, month, day)
                    else null
                }

                insert.clearBindings()
                insert.bindString(1, id)
                insert.bindString(2, title)
                insert.bindNullableLong(3, malId)
                insert.bindNullableLong(4, anilistId)
                insert.bindNullableLong(5, popularity)
                insert.bindNullableString(6, posterUrl)
                insert.bindNullableString(7, posterUrl)
                insert.bindNullableString(8, format)
                insert.bindNullableLong(9, seasonYear)
                insert.bindNullableString(10, mediaStatus)
                insert.bindNullableString(11, source)
                insert.bindNullableLong(12, episodes)
                insert.bindNullableString(13, country)
                insert.bindNullableLong(14, averageScore)
                insert.bindNullableString(15, startDate)
                insert.executeInsert()

                if (!freshCatalog) {
                    update.clearBindings()
                    update.bindString(1, title)
                    update.bindNullableLong(2, malId)
                    update.bindNullableLong(3, anilistId)
                    update.bindNullableLong(4, popularity)
                    update.bindNullableString(5, posterUrl)
                    update.bindNullableString(6, posterUrl)
                    update.bindNullableString(7, format)
                    update.bindNullableLong(8, seasonYear)
                    update.bindNullableString(9, mediaStatus)
                    update.bindNullableString(10, source)
                    update.bindNullableLong(11, episodes)
                    update.bindNullableString(12, country)
                    update.bindNullableLong(13, averageScore)
                    update.bindNullableString(14, startDate)
                    update.bindString(15, id)
                    update.executeUpdateDelete()
                }
                item.optJSONArray("genres")?.let { genres ->
                    for (genreIndex in 0 until genres.length()) {
                        val genre = genres.optString(genreIndex).trim()
                        if (genre.isNotEmpty()) {
                            insertGenre.clearBindings()
                            insertGenre.bindString(1, id)
                            insertGenre.bindString(2, genre)
                            insertGenre.executeInsert()
                        }
                    }
                }
                item.optJSONArray("studios")?.let { studios ->
                    for (studioIndex in 0 until studios.length()) {
                        val studio = studios.optString(studioIndex).trim()
                        if (studio.isNotEmpty()) {
                            insertStudio.clearBindings()
                            insertStudio.bindString(1, id)
                            insertStudio.bindString(2, studio)
                            insertStudio.executeInsert()
                        }
                    }
                }
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
        if (BuildConfig.DEBUG) Log.d(
            "RankOfflineCatalog",
            "pack=${OfficialPackIds.Anime.value} operation=catalog-import items=${entries.length()} " +
                "fresh=$freshCatalog parseMs=$parseMillis " +
                "sqlMs=${(System.nanoTime() - sqlStarted) / 1_000_000L} " +
                "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000L}"
        )
        true
    }

    /** Bundled compatibility path retained until production pack hosting is configured. */
    internal suspend fun updateCatalogIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        val version = context.assets.open(CATALOG_VERSION_ASSET).bufferedReader().use { it.readText().trim() }
        if (dao.metadata(CATALOG_VERSION_KEY) == version) return@withContext false
        val directory = java.io.File(catalogWorkingDirectory, "anime-catalog-fixture-${System.nanoTime()}")
        check(directory.mkdirs() || directory.isDirectory)
        try {
            listOf(CATALOG_ASSET, CATALOG_VERSION_ASSET).forEach { name ->
                context.assets.open(name).use { input ->
                    java.io.File(directory, name).outputStream().use(input::copyTo)
                }
            }
            updateCatalogIfNeeded(directory)
        } finally {
            directory.deleteRecursively()
        }
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
        categoryScore = categoryScore,
        status = status,
        remoteCoverUrl = remoteCoverUrl,
        localCoverPath = localCoverPath,
        format = format,
        seasonYear = seasonYear,
        startDate = startDate,
        studio = studio,
        genre = genre,
        groupId = groupId,
        groupType = groupType,
        rootAnimeId = rootAnimeId,
        memberCount = memberCount,
        ratedCount = ratedCount,
        derived = derived,
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
        format = format,
        seasonYear = seasonYear,
        startDate = startDate,
        coverResolutionState = runCatching { CoverResolutionState.valueOf(coverResolutionState) }
            .getOrDefault(CoverResolutionState.UNKNOWN)
    )

    private fun org.json.JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun org.json.JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key).trim().takeIf(String::isNotEmpty) else null

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
