package org.rankoffline.app

import kotlinx.serialization.Serializable

/** Pack-authored categories. The score scale is ALWAYS 0..10, owned by RankOffline. */
@Serializable
data class RatingCategory(
    val id: String,
    val name: String,
    val description: String,
    val weight: Double,
    val color: String,
    val order: Int,
    val active: Boolean = true
)

/** UI description of an app rule. Never serialized to a .pack or a local rating file. */
data class AdditiveBonus(
    val name: String,
    val description: String,
    val maxValue: Double,
    val color: String
)

object AppRatingRules {
    /** RATING_MIN/RATING_MAX and BONUS_MIN/BONUS_MAX are defined in RatingMath.kt. */
    val additiveBonus = AdditiveBonus(
        name = "Personal taste",
        description = "How much you liked it personally, independent of technical quality",
        maxValue = BONUS_MAX,
        color = "#E04BCF"
    )
}

/** Pack/local schema contains ONLY the categories; the bonus is computed by the app. */
@Serializable
data class RatingSystemConfig(val categories: List<RatingCategory>) {
    val additiveBonus: AdditiveBonus get() = AppRatingRules.additiveBonus
}

@Serializable
data class RatingProfile(val id: String, val name: String, val weights: Map<String, Double>)

@Serializable
data class RatingProfilesConfig(val profiles: List<RatingProfile>)

/** Original definitions are in the manifest. A writable copy is stored outside the pack. */
data class PackRatingDefinition(
    val config: RatingSystemConfig,
    val profiles: RatingProfilesConfig
)

@Serializable
data class MediaSettings(
    val elaborateRatingEnabled: Boolean,
    val ratingConfig: RatingSystemConfig,
    val ratingProfiles: RatingProfilesConfig,
    val mergeEnabled: Boolean,
    val showImages: Boolean = false,
    val titlesOnPodium: Boolean = false,
    val stickyScore: Boolean = true,
    val personalTasteEnabled: Boolean = false
)

@Serializable
data class AllViewSettings(
    val showImages: Boolean = true,
    val titlesOnPodium: Boolean = false,
    val stickyScore: Boolean = true,
    val mergeEnabled: Boolean = false,
    val catalogSortOrder: CatalogSortOrder = CatalogSortOrder.POPULARITY
)

internal fun reconcileProfiles(
    profiles: RatingProfilesConfig,
    categories: List<RatingCategory>,
    fallback: RatingProfilesConfig = RatingProfilesConfig(emptyList())
): RatingProfilesConfig {
    val categoryIds = categories.map { it.id }
    val source = profiles.profiles.distinctBy { it.id }
        .filter { it.id.isNotBlank() }.ifEmpty { fallback.profiles }
    return RatingProfilesConfig(source.map { profile ->
        val weights = categoryIds.associateWith { categoryId ->
            profile.weights[categoryId]?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0
        }
        profile.copy(
            name = profile.name.ifBlank { "Profile" },
            weights = if (weights.isNotEmpty() && weights.values.none { it > 0.0 }) {
                categories.associate { it.id to it.weight }
            } else weights
        )
    })
}

internal fun RatingProfilesConfig.withCategoryAdded(categoryId: String): RatingProfilesConfig =
    copy(profiles = profiles.map { it.copy(weights = it.weights + (categoryId to 0.0)) })

internal fun RatingProfilesConfig.withCategoryRemoved(categoryId: String): RatingProfilesConfig =
    copy(profiles = profiles.map { it.copy(weights = it.weights - categoryId) })

internal fun RatingProfilesConfig.withProfileDeleted(profileId: String): RatingProfilesConfig =
    if (profiles.size <= 1) this else copy(profiles = profiles.filterNot { it.id == profileId })

internal fun RatingProfilesConfig.resolveProfile(profileId: String?): RatingProfile =
    profiles.firstOrNull { it.id == profileId }
        ?: profiles.firstOrNull { it.id == "general" }
        ?: profiles.firstOrNull()
        ?: error("No rating profiles defined in this pack")

internal fun RatingSystemConfig.withProfile(profile: RatingProfile): RatingSystemConfig =
    copy(categories = categories.map { it.copy(weight = profile.weights[it.id] ?: 0.0) })

/** Validate only pack-editable definitions; app-owned score/bonus constants are not inputs. */
internal fun validateRatingConfiguration(
    config: RatingSystemConfig,
    profiles: RatingProfilesConfig,
    requireProfiles: Boolean = true
) {
    require(config.categories.size in 1..48 && (profiles.profiles.isNotEmpty() || !requireProfiles))
    val categories = config.categories
    require(categories.map { it.id }.distinct().size == categories.size)
    require(categories.map { it.order }.distinct().size == categories.size)
    require(categories.all {
        it.id.matches(Regex("[a-z][a-z0-9_]{0,63}")) && it.name.length in 1..160 &&
            it.description.length <= 1024 && it.color.matches(Regex("#[0-9A-Fa-f]{6}")) &&
            it.order >= 0 && it.weight.isFinite() && it.weight in 0.0..100.0
    })
    require(categories.any { it.active && it.weight > 0.0 }) {
        "At least one active category must have positive weight"
    }
    require(profiles.profiles.size <= 32 && profiles.profiles.map { it.id }.distinct().size == profiles.profiles.size)
    val ids = categories.mapTo(hashSetOf()) { it.id }
    profiles.profiles.forEach { profile ->
        require(profile.id.matches(Regex("[a-z][a-z0-9_]{0,63}")) && profile.name.length in 1..160)
        require(profile.weights.keys == ids && profile.weights.values.all { it.isFinite() && it in 0.0..100.0 })
        require(profile.weights.values.sum() > 0.0)
    }
}
