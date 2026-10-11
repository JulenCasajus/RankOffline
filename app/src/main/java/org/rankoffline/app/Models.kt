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
    val malId: Long? = null,
    val anilistId: Long? = null,
    val popularity: Int? = null,
    val popularityRank: Int? = null,
    val finalScore: Double? = null,
    val baseScore: Double? = null,
    val additiveScore: Double? = null,
    val status: String? = null,
    val remoteCoverUrl: String? = null,
    val localCoverPath: String? = null,
    val coverResolutionState: CoverResolutionState = CoverResolutionState.UNKNOWN
)

enum class CatalogSortOrder { POPULARITY, TITLE_ASC, TITLE_DESC }

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
    val finalScore: Double? = null
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
