package org.rankoffline.app

const val RATING_MIN = 0.0
const val RATING_MAX = 10.0
const val RATING_STEP = 0.5
const val BONUS_MIN = 0.0
const val BONUS_MAX = 1.0
const val BONUS_STEP = 0.1
const val WEIGHT_MIN = 0.0
const val WEIGHT_MAX = 100.0
const val WEIGHT_STEP = 5.0

data class ScoreResult(val qualityScore: Double?, val finalScore: Double?)

object ScoreCalculator {
    /** Missing/not-applicable categories are excluded and remaining weights renormalized. */
    fun calculate(
        categoryScores: Map<String, Double?>,
        additiveScore: Double,
        config: RatingSystemConfig
    ): ScoreResult {
        val applicable = mutableListOf<Pair<Double, Double>>()
        for (category in config.categories) {
            if (!category.active || !category.weight.isFinite() || category.weight <= 0.0) continue
            val score = categoryScores[category.id] ?: continue
            if (!score.isFinite() || score !in RATING_MIN..RATING_MAX) return ScoreResult(null, null)
            applicable += category.weight to score
        }

        val totalWeight = applicable.sumOf { it.first }
        if (!totalWeight.isFinite() || totalWeight <= 0.0) return ScoreResult(null, null)
        val quality = applicable.sumOf { (weight, score) -> score * (weight / totalWeight) }
        if (!quality.isFinite()) return ScoreResult(null, null)

        val bonusConfig = config.additiveBonus
        val bonus = if (additiveScore.isFinite() && bonusConfig.maxValue.isFinite()) {
            additiveScore.coerceIn(0.0, bonusConfig.maxValue.coerceAtLeast(0.0))
        } else 0.0
        return ScoreResult(quality, (quality + bonus).coerceIn(RATING_MIN, RATING_MAX))
    }
}

data class RatingDraft(
    val categoryScores: Map<String, Double?> = emptyMap(),
    val additiveScore: Double = 0.0,
    val notes: String = "",
    val status: String = "",
    val finalScore: Double? = null,
    val qualityScore: Double? = null,
    val profileId: String? = null
) {
    /** Explicit editor initialization, never applied automatically to existing user ratings. */
    fun withDefaultCategoryScores(config: RatingSystemConfig): RatingDraft =
        if (categoryScores.isNotEmpty()) this else
            copy(categoryScores = config.categories.filter { it.active }.associate { it.id to 5.0 })

    fun baseScore(config: RatingSystemConfig): Double? =
        ScoreCalculator.calculate(categoryScores, additiveScore, config).qualityScore

    fun calculatedFinalScore(config: RatingSystemConfig): Double? =
        ScoreCalculator.calculate(categoryScores, additiveScore, config).finalScore
}

/** Mean over valid rated members of ONE pack; no assumptions about group content or type. */
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
        qualityScore = qualityScore ?: finalScore?.minus(additiveScore ?: 0.0)?.coerceIn(RATING_MIN, RATING_MAX)
    )
}

fun deriveMediaGroupRating(
    ratings: List<MediaRatingEntity>,
    scores: List<MediaRatingScoreEntity>
): DerivedGroupRating {
    val packIds = (ratings.asSequence().map { it.packId } + scores.asSequence().map { it.packId }).distinct().take(2).toList()
    require(packIds.size <= 1) { "A group may contain ratings from only one pack" }

    // Qualified keys prevent accidental cross-pack matches if queries are changed later.
    val rated = ratings.filter { it.finalScore?.isFinite() == true && it.finalScore in RATING_MIN..RATING_MAX }
        .associateBy { it.packId to it.mediaId }

    fun Iterable<Double?>.averageValid(): Double? =
        mapNotNull { it?.takeIf { value -> value.isFinite() && value in RATING_MIN..RATING_MAX } }
            .takeIf { it.isNotEmpty() }?.average()

    val categoryAverages = scores.asSequence()
        .filter { (it.packId to it.mediaId) in rated && it.applicable &&
            it.score?.isFinite() == true && it.score in RATING_MIN..RATING_MAX }
        .groupBy { it.categoryId }
        .mapValues { (_, values) -> values.mapNotNull { it.score }.average() }

    return DerivedGroupRating(
        finalScore = rated.values.map { it.finalScore }.averageValid(),
        qualityScore = rated.values.map { it.qualityScore }.averageValid(),
        additiveScore = rated.values.map { it.additiveScore }.averageValid(),
        categoryScores = categoryAverages,
        ratedCount = rated.size
    )
}
