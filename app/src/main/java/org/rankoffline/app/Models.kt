package org.rankoffline.app

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.roundToInt

sealed interface UserMessage {
    data class Text(@StringRes val resource: Int, val arguments: List<Any> = emptyList()) : UserMessage
    data class Plural(@PluralsRes val resource: Int, val quantity: Int, val arguments: List<Any>) : UserMessage

    fun resolve(context: Context): String = when (this) {
        is Text -> context.getString(resource, *arguments.toTypedArray())
        is Plural -> context.resources.getQuantityString(resource, quantity, *arguments.toTypedArray())
    }
}

data class Anime(
    val id: String,
    val title: String,
    val mediaType: MediaType = MediaType.ANIME,
    val malId: Long? = null,
    val anilistId: Long? = null,
    val popularity: Int? = null,
    val popularityRank: Int? = null,
    val finalScore: Double? = null,
    val baseScore: Double? = null,
    val additiveScore: Double? = null,
    val categoryScore: Double? = null,
    val status: String? = null,
    val remoteCoverUrl: String? = null,
    val localCoverPath: String? = null,
    val coverResolutionState: CoverResolutionState = CoverResolutionState.UNKNOWN,
    val format: String? = null,
    val seasonYear: Int? = null,
    val startDate: String? = null,
    val studio: String? = null,
    val groupId: String? = null,
    val groupType: String? = null,
    val rootAnimeId: String? = null,
    val memberCount: Int = 0,
    val ratedCount: Int = 0,
    val derived: Boolean = false
)

enum class CatalogSortOrder { POPULARITY, POPULARITY_REVERSE, TITLE_ASC, TITLE_DESC }

val CatalogSortOrder.isReversed: Boolean
    get() = this == CatalogSortOrder.POPULARITY_REVERSE || this == CatalogSortOrder.TITLE_DESC

fun CatalogSortOrder.withReversed(reversed: Boolean): CatalogSortOrder = when (this) {
    CatalogSortOrder.POPULARITY,
    CatalogSortOrder.POPULARITY_REVERSE -> if (reversed) CatalogSortOrder.POPULARITY_REVERSE else CatalogSortOrder.POPULARITY
    CatalogSortOrder.TITLE_ASC,
    CatalogSortOrder.TITLE_DESC -> if (reversed) CatalogSortOrder.TITLE_DESC else CatalogSortOrder.TITLE_ASC
}

fun CatalogSortOrder.inverted(): CatalogSortOrder = withReversed(!isReversed)

enum class RankingSortOrder { FINAL_SCORE, QUALITY_SCORE, PERSONAL_TASTE, CATEGORY_SCORE }

const val SERIES_FORMAT_FILTER = "SERIES"
const val MOVIES_FORMAT_FILTER = "MOVIES"
const val SPECIAL_FORMAT_FILTER = "SPECIAL"
const val MUSIC_FORMAT_FILTER = "MUSIC"
val NORMALIZED_FORMAT_FILTERS = listOf(
    SERIES_FORMAT_FILTER,
    MOVIES_FORMAT_FILTER,
    SPECIAL_FORMAT_FILTER,
    MUSIC_FORMAT_FILTER
)

internal fun normalizedAnimeFormat(raw: String?): String? = when (raw?.uppercase(Locale.ROOT)) {
    "TV", "TV_SHORT", "ONA", SERIES_FORMAT_FILTER -> SERIES_FORMAT_FILTER
    "MOVIE", MOVIES_FORMAT_FILTER -> MOVIES_FORMAT_FILTER
    "OVA", "SPECIAL" -> SPECIAL_FORMAT_FILTER
    "MUSIC" -> MUSIC_FORMAT_FILTER
    null, "" -> null
    else -> null
}

internal fun normalizedFormatOptions(rawFormats: Iterable<String>): List<String> {
    val available = rawFormats.mapNotNull(::normalizedAnimeFormat).toSet()
    return NORMALIZED_FORMAT_FILTERS.filter(available::contains)
}

internal fun Anime.metadataYear(): Int? = seasonYear ?: startDate?.take(4)?.toIntOrNull()

internal fun metadataSubtitleSegments(format: String?, year: Int?, studio: String?): List<String> =
    listOfNotNull(format?.takeIf(String::isNotBlank), year?.toString(), studio?.takeIf(String::isNotBlank))

internal fun joinMetadataSubtitle(segments: List<String>): String = segments.joinToString(" · ")

data class GroupDetail(
    val group: AnimeGroupEntity,
    val members: List<Anime>,
    val explicitRating: GroupRatingEntity?,
    val derivedRating: DerivedGroupRating
)

data class GroupDetailUi(
    val id: String,
    val mediaType: MediaType,
    val type: String,
    val displayTitle: String,
    val representativeMediaId: String?,
    val members: List<Anime>,
    val explicitFinalScore: Double?,
    val derivedRating: DerivedGroupRating
)

fun GroupDetail.toUi(): GroupDetailUi = GroupDetailUi(
    id = group.id,
    mediaType = MediaType.ANIME,
    type = group.type,
    displayTitle = group.displayTitle,
    representativeMediaId = group.rootAnimeId,
    members = members,
    explicitFinalScore = explicitRating?.finalScore,
    derivedRating = derivedRating
)

fun MediaGroupDetail.toUi(): GroupDetailUi = GroupDetailUi(
    id = group.id,
    mediaType = group.mediaType,
    type = group.groupType,
    displayTitle = group.displayTitle,
    representativeMediaId = group.representativeMediaId ?: members.firstOrNull()?.id,
    members = members.map { MediaListEntry.Item(it).toAnimeDisplay() },
    explicitFinalScore = explicitRating?.finalScore,
    derivedRating = derivedRating
)

enum class AnimeListRowAction { RATE, GROUP_DETAILS }

internal fun animeListRowAction(anime: Anime, memberCountClicked: Boolean): AnimeListRowAction =
    if (anime.groupId != null && memberCountClicked) AnimeListRowAction.GROUP_DETAILS else AnimeListRowAction.RATE

fun Anime.coverTarget(): Anime = when {
    groupId == null -> this
    mediaType == MediaType.ANIME || mediaType == MediaType.MANGA ->
        rootAnimeId?.let { copy(id = it, groupId = null, groupType = null) } ?: this
    else -> this
}

internal fun Anime.supportsRemoteGroupCover(): Boolean =
    groupId != null && mediaType in setOf(MediaType.MOVIE, MediaType.SERIES)

data class DerivedGroupRating(
    val finalScore: Double?,
    val qualityScore: Double?,
    val additiveScore: Double?,
    val categoryScores: Map<String, Double>,
    val ratedCount: Int
) {
    fun asDraft(): RatingDraft = RatingDraft(
        categoryScores = categoryScores.mapValues { (_, value) -> snapRatingValue(value) },
        additiveScore = snapBonusValue(additiveScore ?: 0.0) ?: 0.0,
        finalScore = null,
        qualityScore = qualityScore ?: finalScore?.minus(additiveScore ?: 0.0)?.coerceIn(RATING_MIN, RATING_MAX)
    )
}

internal data class GroupRatingInput(
    val id: String,
    val finalScore: Double?,
    val qualityScore: Double?,
    val additiveScore: Double?
)

internal data class GroupRatingScoreInput(
    val memberId: String,
    val categoryId: String,
    val score: Double?,
    val applicable: Boolean
)

internal fun deriveGroupRatingFromInputs(
    ratings: List<GroupRatingInput>,
    scores: List<GroupRatingScoreInput>
): DerivedGroupRating {
    val rated = ratings.filter { it.finalScore?.isFinite() == true }
    val ratedIds = rated.mapTo(mutableSetOf()) { it.id }
    fun Iterable<Double?>.averageOrNull(): Double? {
        val values = filterNotNull().filter(Double::isFinite)
        return values.takeIf { it.isNotEmpty() }?.average()
    }
    val categories = scores.asSequence()
        .filter { it.memberId in ratedIds && it.applicable && it.score?.isFinite() == true }
        .groupBy { it.categoryId }
        .mapValues { (_, values) -> values.mapNotNull { it.score }.average() }
    return DerivedGroupRating(
        finalScore = rated.map { it.finalScore }.averageOrNull(),
        qualityScore = rated.map { it.qualityScore }.averageOrNull(),
        additiveScore = rated.map { it.additiveScore }.averageOrNull(),
        categoryScores = categories,
        ratedCount = rated.size
    )
}

fun deriveGroupRating(
    ratings: List<RatingEntity>,
    scores: List<RatingScoreEntity>
): DerivedGroupRating = deriveGroupRatingFromInputs(
    ratings = ratings.map { GroupRatingInput(it.animeId, it.finalScore, it.qualityScore, it.additiveScore) },
    scores = scores.map { GroupRatingScoreInput(it.animeId, it.categoryId, it.score, it.applicable) }
)

fun deriveMediaGroupRating(
    ratings: List<MediaRatingEntity>,
    scores: List<MediaRatingScoreEntity>
): DerivedGroupRating = deriveGroupRatingFromInputs(
    ratings = ratings.map { GroupRatingInput(it.mediaId, it.finalScore, it.qualityScore, it.additiveScore) },
    scores = scores.map { GroupRatingScoreInput(it.mediaId, it.categoryId, it.score, it.applicable) }
)

fun Anime.rankingDisplayScore(sortOrder: RankingSortOrder): Double? = when (sortOrder) {
    RankingSortOrder.FINAL_SCORE -> finalScore
    RankingSortOrder.QUALITY_SCORE -> baseScore
    RankingSortOrder.PERSONAL_TASTE -> additiveScore?.times(10.0)
    RankingSortOrder.CATEGORY_SCORE -> categoryScore
}?.takeIf { it.isFinite() && it in RATING_MIN..RATING_MAX }

data class AnimeFilters(
    val genre: String? = null,
    val format: String? = null,
    val year: Int? = null,
    val studio: String? = null
) {
    val activeCount: Int
        get() = listOf(genre, format, year, studio).count { it != null }
}

data class AnimeFilterOptions(
    val genres: List<String> = emptyList(),
    val formats: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
    val studios: List<String> = emptyList()
)

enum class CoverResolutionState { UNKNOWN, AVAILABLE, NOT_FOUND }

enum class CoverDownloadState { IDLE, DOWNLOADING }

enum class PopularCoverBatch(val count: Int) {
    TOP_100(100),
    TOP_500(500),
    TOP_1000(1000),
    TOP_5000(5000)
}

enum class CoverTapAction { DOWNLOAD, OPEN_VIEWER }

fun coverTapAction(localCoverPath: String?): CoverTapAction =
    if (localCoverPath.isNullOrBlank()) CoverTapAction.DOWNLOAD else CoverTapAction.OPEN_VIEWER

internal fun dispatchCoverTap(
    localCoverPath: String?,
    onMissingCoverClick: () -> Unit,
    onCoverClick: () -> Unit
) {
    when (coverTapAction(localCoverPath)) {
        CoverTapAction.DOWNLOAD -> onMissingCoverClick()
        CoverTapAction.OPEN_VIEWER -> onCoverClick()
    }
}

internal fun dispatchTop3ScoreTap(onOpenRating: () -> Unit) = onOpenRating()

sealed interface CoverDownloadResult {
    data object Downloaded : CoverDownloadResult
    data object AlreadyAvailable : CoverDownloadResult
    data object AlreadyInProgress : CoverDownloadResult
    data object NotFound : CoverDownloadResult
    data object NetworkError : CoverDownloadResult
    data object TemporaryError : CoverDownloadResult
}

object CoverResolutionPolicy {
    fun afterDownload(
        current: CoverResolutionState,
        result: CoverDownloadResult
    ): CoverResolutionState = when (result) {
        CoverDownloadResult.Downloaded,
        CoverDownloadResult.AlreadyAvailable -> CoverResolutionState.AVAILABLE
        CoverDownloadResult.NotFound -> CoverResolutionState.NOT_FOUND
        CoverDownloadResult.AlreadyInProgress,
        CoverDownloadResult.NetworkError,
        CoverDownloadResult.TemporaryError -> current
    }
}

data class PopularAnimeEntry(
    val anilistId: Long,
    val malId: Long?,
    val popularity: Int?,
    val coverUrl: String?
)

data class CoverPreloadCandidate(val anime: Anime, val coverUrl: String?)

data class CoverPreloadPlan(
    val target: Int,
    val existing: Int,
    val pending: List<CoverPreloadCandidate>,
    val unmatched: Int
)

object PopularCoverPlanner {
    fun plan(entries: List<PopularAnimeEntry>, localAnime: List<Anime>, limit: Int): CoverPreloadPlan {
        val selected = entries.take(limit.coerceAtLeast(0))
        val byAnilist = localAnime.groupBy { it.anilistId }.filterKeys { it != null }
        val byMal = localAnime.groupBy { it.malId }.filterKeys { it != null }
        val incomingMalCounts = selected.mapNotNull { it.malId }.groupingBy { it }.eachCount()
        val seenAnimeIds = mutableSetOf<String>()
        var existing = 0
        var unmatched = 0
        val pending = buildList {
            selected.forEach { entry ->
                val anilistMatches = byAnilist[entry.anilistId].orEmpty()
                val malId = entry.malId
                val anime = if (anilistMatches.size == 1) {
                    anilistMatches.single()
                } else {
                    val malMatches = malId?.let { byMal[it] }.orEmpty()
                    malMatches.singleOrNull()?.takeIf { candidate ->
                        malId != null && incomingMalCounts[malId] == 1 &&
                            candidate.anilistId in listOf(null, entry.anilistId)
                    }
                }
                when {
                    anime == null -> unmatched++
                    !seenAnimeIds.add(anime.id) -> Unit
                    !anime.localCoverPath.isNullOrBlank() -> existing++
                    else -> add(CoverPreloadCandidate(anime, entry.coverUrl))
                }
            }
        }
        return CoverPreloadPlan(selected.size, existing, pending, unmatched)
    }
}

enum class CoverPreloadStatus {
    IDLE,
    FETCHING_POPULARITY,
    APPLYING_POPULARITY,
    DOWNLOADING_COVERS,
    COMPLETED,
    FAILED
}

val CoverPreloadStatus.isTerminal: Boolean
    get() = this == CoverPreloadStatus.COMPLETED || this == CoverPreloadStatus.FAILED

data class CoverPreloadProgress(
    val status: CoverPreloadStatus = CoverPreloadStatus.IDLE,
    val target: Int = 0,
    val popularityProcessed: Int = 0,
    val processed: Int = 0,
    val existing: Int = 0,
    val downloaded: Int = 0,
    val errors: Int = 0,
    val popularityUpdated: Boolean = false,
    val message: UserMessage? = null
) {
    val isActive: Boolean get() = status in setOf(
        CoverPreloadStatus.FETCHING_POPULARITY,
        CoverPreloadStatus.APPLYING_POPULARITY,
        CoverPreloadStatus.DOWNLOADING_COVERS
    )
    val popularityFraction: Float
        get() = if (target <= 0) 0f else (popularityProcessed.toFloat() / target).coerceIn(0f, 1f)
    val fraction: Float get() = if (target <= 0) 0f else (processed.toFloat() / target).coerceIn(0f, 1f)
}

object TopUpdateProgress {
    fun start(target: Int) = CoverPreloadProgress(
        status = CoverPreloadStatus.FETCHING_POPULARITY,
        target = target
    )

    fun popularityFetched(progress: CoverPreloadProgress, count: Int) = progress.copy(
        popularityProcessed = count.coerceIn(0, progress.target)
    )

    fun applyingPopularity(progress: CoverPreloadProgress) = progress.copy(
        status = CoverPreloadStatus.APPLYING_POPULARITY,
        popularityProcessed = progress.target
    )

    fun downloadingCovers(
        progress: CoverPreloadProgress,
        processed: Int,
        existing: Int,
        errors: Int
    ) = progress.copy(
        status = CoverPreloadStatus.DOWNLOADING_COVERS,
        popularityProcessed = progress.target,
        popularityUpdated = true,
        processed = processed,
        existing = existing,
        errors = errors
    )

    fun completed(progress: CoverPreloadProgress) = progress.copy(
        status = CoverPreloadStatus.COMPLETED,
        popularityProcessed = progress.target,
        processed = progress.target
    )
}

fun CoverPreloadProgress.shouldClearAfterLeavingSettings(): Boolean =
    status.isTerminal

data class ScoreResult(val qualityScore: Double?, val finalScore: Double?)

const val RATING_MIN = 0.0
const val RATING_MAX = 10.0
const val RATING_STEP = 0.5
const val RATING_SLIDER_STEPS = 19
const val BONUS_MIN = 0.0
const val BONUS_MAX = 1.0
const val BONUS_STEP = 0.1
const val BONUS_SLIDER_STEPS = 9
const val WEIGHT_MIN = 0.0
const val WEIGHT_MAX = 100.0
const val WEIGHT_STEP = 5.0
const val WEIGHT_SLIDER_STEPS = 19

fun discreteValues(min: Double, max: Double, step: Double): List<Double> {
    require(min.isFinite() && max.isFinite() && step.isFinite() && max > min && step > 0.0)
    val intervals = ((max - min) / step).roundToInt()
    require(intervals > 0 && kotlin.math.abs(min + intervals * step - max) < 1e-9)
    val decimalMin = BigDecimal.valueOf(min)
    val decimalStep = BigDecimal.valueOf(step)
    return (0..intervals).map { index ->
        decimalMin.add(decimalStep.multiply(BigDecimal.valueOf(index.toLong()))).toDouble()
    }
}

fun interiorDiscreteValues(min: Double, max: Double, step: Double): List<Double> =
    discreteValues(min, max, step).drop(1).dropLast(1)

fun snapDiscreteValue(value: Double, min: Double, max: Double, step: Double): Double? {
    if (!value.isFinite() || !min.isFinite() || !max.isFinite() || !step.isFinite() ||
        value !in min..max || max <= min || step <= 0.0
    ) return null
    val index = ((value - min) / step).roundToInt()
    val intervals = ((max - min) / step).roundToInt()
    if (index !in 0..intervals) return null
    return BigDecimal.valueOf(min)
        .add(BigDecimal.valueOf(step).multiply(BigDecimal.valueOf(index.toLong())))
        .toDouble()
}

fun snapRatingValue(value: Double): Double? {
    return snapDiscreteValue(value, RATING_MIN, RATING_MAX, RATING_STEP)
}

fun isDiscreteRatingValue(value: Double): Boolean = snapRatingValue(value) == value

private val DIRECT_RATING_PATTERN = Regex("^(?:\\d{1,2})(?:[.,]\\d{1,2})?$")

fun parseDirectRatingValue(value: String): Double? {
    val normalized = value.trim().takeIf(DIRECT_RATING_PATTERN::matches)?.replace(',', '.') ?: return null
    return normalizeDirectRatingValue(normalized.toDoubleOrNull() ?: return null)
}

fun normalizeDirectRatingValue(value: Double): Double? {
    if (!value.isFinite() || value !in RATING_MIN..RATING_MAX) return null
    val decimal = BigDecimal.valueOf(value).stripTrailingZeros()
    if (decimal.scale().coerceAtLeast(0) > 2) return null
    return decimal.toDouble()
}

fun finalScoreFromQuality(qualityScore: Double, additiveScore: Double): Double? {
    if (!qualityScore.isFinite() || qualityScore !in RATING_MIN..RATING_MAX ||
        !additiveScore.isFinite() || additiveScore !in BONUS_MIN..BONUS_MAX
    ) return null
    return BigDecimal.valueOf(qualityScore)
        .add(BigDecimal.valueOf(additiveScore))
        .min(BigDecimal.valueOf(RATING_MAX))
        .toDouble()
}

fun snapBonusValue(value: Double): Double? =
    snapDiscreteValue(value, BONUS_MIN, BONUS_MAX, BONUS_STEP)

fun snapWeight(value: Double): Double? =
    snapDiscreteValue(value, WEIGHT_MIN, WEIGHT_MAX, WEIGHT_STEP)

private val HEX_COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")

fun normalizeHexColor(value: String): String? = value.trim().takeIf(HEX_COLOR_PATTERN::matches)
    ?.uppercase(Locale.ROOT)

fun RatingCategory.withHexColor(value: String): RatingCategory? =
    normalizeHexColor(value)?.let { copy(color = it) }

object ScoreCalculator {
    fun calculate(categoryScores: Map<String, Double?>, additiveScore: Double, config: RatingSystemConfig): ScoreResult {
        val applicable = mutableListOf<Pair<Double, Double>>()
        config.categories
            .filter { it.active && it.weight.isFinite() && it.weight > 0.0 }
            .forEach { category ->
                val score = categoryScores[category.id] ?: return@forEach
                if (!score.isFinite() || score !in RATING_MIN..RATING_MAX) {
                    return ScoreResult(null, null)
                }
                applicable += category.weight to score
            }
        val totalWeight = applicable.sumOf { it.first }
        if (!totalWeight.isFinite() || totalWeight <= 0.0 || applicable.isEmpty()) {
            return ScoreResult(null, null)
        }
        val quality = applicable.sumOf { (weight, score) -> score * (weight / totalWeight) }
        if (!quality.isFinite()) return ScoreResult(null, null)

        val bonusConfig = config.additiveBonus
        val safeBonus = if (bonusConfig != null && additiveScore.isFinite() && bonusConfig.maxValue.isFinite()) {
            additiveScore.coerceIn(0.0, bonusConfig.maxValue.coerceAtLeast(0.0))
        } else {
            0.0
        }
        return ScoreResult(quality, (quality + safeBonus).coerceIn(RATING_MIN, RATING_MAX))
    }
}

data class RatingDraft(
    val categoryScores: Map<String, Double?> = emptyMap(),
    val additiveScore: Double = 0.0,
    val notes: String = "",
    val status: String = "Completed",
    val finalScore: Double? = null,
    val qualityScore: Double? = null,
    val profileId: String? = null
) {
    fun withDefaultCategoryScores(config: RatingSystemConfig): RatingDraft {
        if (categoryScores.isNotEmpty()) return this
        return copy(categoryScores = config.categories.filter { it.active }.associate { it.id to 5.0 })
    }

    fun baseScore(config: RatingSystemConfig): Double? {
        return ScoreCalculator.calculate(categoryScores, additiveScore, config).qualityScore
    }

    fun calculatedFinalScore(config: RatingSystemConfig): Double? {
        return ScoreCalculator.calculate(categoryScores, additiveScore, config).finalScore
    }
}
