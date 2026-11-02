package org.rankoffline.app

import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.roundToInt

fun discreteValues(min: Double, max: Double, step: Double): List<Double> {
    require(min.isFinite() && max.isFinite() && step.isFinite() && max > min && step > 0.0)
    val intervals = ((max - min) / step).roundToInt()
    require(intervals > 0 && abs(min + intervals * step - max) < 1e-9)
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
    val intervals = ((max - min) / step).roundToInt()
    if (intervals <= 0 || abs(min + intervals * step - max) >= 1e-9) return null
    val index = ((value - min) / step).roundToInt()
    if (index !in 0..intervals) return null
    return BigDecimal.valueOf(min)
        .add(BigDecimal.valueOf(step).multiply(BigDecimal.valueOf(index.toLong())))
        .toDouble()
}

fun snapRatingValue(value: Double): Double? = snapDiscreteValue(value, RATING_MIN, RATING_MAX, RATING_STEP)
fun isDiscreteRatingValue(value: Double): Boolean = snapRatingValue(value) == value
fun snapBonusValue(value: Double): Double? = snapDiscreteValue(value, BONUS_MIN, BONUS_MAX, BONUS_STEP)
fun snapWeight(value: Double): Double? = snapDiscreteValue(value, WEIGHT_MIN, WEIGHT_MAX, WEIGHT_STEP)

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


