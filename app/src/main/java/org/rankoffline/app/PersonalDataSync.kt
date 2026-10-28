package org.rankoffline.app

/** Pack-scoped identity used by backups and future optional external connectors. */
data class OwnedMediaIdentity(val packId: PackId, val canonicalId: String) {
    init { require(canonicalId.isNotBlank()) }
}

data class ExternalWorkId(val provider: String, val value: String) {
    init {
        require(provider.matches(Regex("[a-z][a-z0-9_-]{1,63}")))
        require(value.isNotBlank() && value.length <= 500)
    }
}

data class RatingScale(val minimum: Double, val maximum: Double) {
    init { require(minimum.isFinite() && maximum.isFinite() && maximum > minimum) }
}

data class ExternalRatingRecord(
    val externalId: ExternalWorkId,
    val score: Double,
    val scale: RatingScale,
    val updatedAt: Long
)

data class MappedExternalRating(
    val identity: OwnedMediaIdentity,
    val convertedScore: Double,
    val originalScore: Double,
    val originalScale: RatingScale,
    val updatedAt: Long
)

data class ExternalRatingPreview(
    val applicable: List<MappedExternalRating>,
    val unchanged: Int,
    val missing: Int,
    val ambiguous: Int,
    val invalid: Int
)

internal fun convertRatingScale(
    value: Double,
    source: RatingScale,
    target: RatingScale = RatingScale(0.0, 10.0)
): Double? {
    if (!value.isFinite() || value !in source.minimum..source.maximum) return null
    val fraction = (value - source.minimum) / (source.maximum - source.minimum)
    return target.minimum + fraction * (target.maximum - target.minimum)
}

/**
 * Dry-run only: connectors remain absent until provider authorization is settled. Newer records
 * win; ties keep the local value, making an identical re-import idempotent.
 */
internal fun previewExternalRatings(
    records: List<ExternalRatingRecord>,
    mappings: Map<ExternalWorkId, Set<OwnedMediaIdentity>>,
    localUpdatedAt: Map<OwnedMediaIdentity, Long>,
    targetScale: RatingScale = RatingScale(0.0, 10.0)
): ExternalRatingPreview {
    var missing = 0
    var ambiguous = 0
    var invalid = 0
    var unchanged = 0
    val newest = linkedMapOf<OwnedMediaIdentity, MappedExternalRating>()
    records.forEach { record ->
        val identities = mappings[record.externalId].orEmpty()
        when {
            identities.isEmpty() -> missing++
            identities.size != 1 -> ambiguous++
            else -> {
                val converted = convertRatingScale(record.score, record.scale, targetScale)
                if (converted == null || record.updatedAt < 0L) {
                    invalid++
                } else {
                    val identity = identities.single()
                    val mapped = MappedExternalRating(
                        identity,
                        converted,
                        record.score,
                        record.scale,
                        record.updatedAt
                    )
                    val prior = newest[identity]
                    if (prior == null || mapped.updatedAt > prior.updatedAt) newest[identity] = mapped
                }
            }
        }
    }
    val applicable = newest.values.filter { mapped ->
        val applies = mapped.updatedAt > (localUpdatedAt[mapped.identity] ?: Long.MIN_VALUE)
        if (!applies) unchanged++
        applies
    }
    return ExternalRatingPreview(applicable, unchanged, missing, ambiguous, invalid)
}
