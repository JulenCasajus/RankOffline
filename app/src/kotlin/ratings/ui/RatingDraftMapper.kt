package org.rankoffline.app

/**
 * Shared, category-agnostic rating calculations. Both item and group repositories can persist
 * these values as their own independent entity types; no item->group adapter is required.
 */
internal data class PreparedRating(
    val additiveScore: Double,
    val qualityScore: Double?,
    val finalScore: Double?,
    val notes: String,
    val status: String,
    val updatedAt: Long,
    val profileId: String?,
    /** Null = do not replace existing category rows (simple mode). Empty = clear them. */
    val categoryScores: Map<String, Double?>? = null
)

internal fun prepareSimpleRating(
    draft: RatingDraft,
    qualityScore: Double,
    finalScore: Double,
    updatedAt: Long
): PreparedRating = PreparedRating(
    additiveScore = requireNotNull(snapBonusValue(draft.additiveScore)) { "Invalid additive score" },
    qualityScore = requireNotNull(normalizeDirectRatingValue(qualityScore)) { "Invalid quality score" },
    finalScore = requireNotNull(normalizeDirectRatingValue(finalScore)) { "Invalid final score" },
    notes = draft.notes,
    status = draft.status,
    updatedAt = updatedAt,
    profileId = draft.profileId
)

internal fun prepareElaborateRating(
    draft: RatingDraft,
    config: RatingSystemConfig,
    finalScoreOverride: Double?,
    profileId: String?,
    updatedAt: Long
): PreparedRating {
    require(draft.categoryScores.keys.all(String::isNotBlank)) { "Blank rating category" }
    require(draft.categoryScores.values.all { it == null || isDiscreteRatingValue(it) }) {
        "Invalid category score"
    }
    val bonus = requireNotNull(snapBonusValue(draft.additiveScore)) { "Invalid additive score" }
    val computed = ScoreCalculator.calculate(draft.categoryScores, bonus, config)
    val final = finalScoreOverride?.let { value ->
        requireNotNull(normalizeDirectRatingValue(value)) { "Invalid final score override" }
    } ?: computed.finalScore
    return PreparedRating(
        additiveScore = bonus,
        qualityScore = computed.qualityScore,
        finalScore = final,
        notes = draft.notes,
        status = draft.status,
        updatedAt = updatedAt,
        profileId = profileId ?: draft.profileId,
        categoryScores = draft.categoryScores
    )
}
