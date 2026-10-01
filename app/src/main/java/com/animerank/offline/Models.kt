package com.animerank.offline

data class Anime(
    val id: String,
    val title: String,
    val malId: Long? = null,
    val anilistId: Long? = null,
    val finalScore: Double? = null,
    val baseScore: Double? = null,
    val additiveScore: Double? = null,
    val status: String? = null,
    val posterUrl: String? = null
)

data class CategoryScore(
    val categoryId: String,
    val score: Double?
)

data class RatingDraft(
    val categoryScores: Map<String, Double?> = emptyMap(),
    val additiveScore: Double = 0.0,
    val notes: String = "",
    val status: String = "Completed"
) {
    fun baseScore(config: RatingSystemConfig): Double? {
        val applicable = config.categories.filter { categoryScores[it.id] != null }
        val totalWeight = applicable.sumOf { it.weight }
        if (totalWeight == 0.0 || applicable.isEmpty()) return null
        return applicable.sumOf { (categoryScores[it.id] ?: 0.0) * it.weight } / totalWeight
    }

    fun finalScore(config: RatingSystemConfig): Double? {
        val base = baseScore(config) ?: return null
        val additive = if (config.additiveBonus != null) additiveScore else 0.0
        return minOf(10.0, base + additive)
    }
}

// Backward compatibility for default categories
val categorySpecs = listOf(
    CategorySpec("writing", "Writing", 0.35, listOf("Story", "Pacing", "Themes", "Dialogue", "Consistency", "Ending")),
    CategorySpec("characters", "Characters", 0.25, listOf("Main cast", "Side cast", "Antagonists", "Development", "Depth", "Dynamics")),
    CategorySpec("engagement", "Engagement", 0.20, listOf("Emotional impact", "Entertainment value", "Viewer investment", "Tension", "Atmosphere", "Memorability")),
    CategorySpec("visuals", "Visuals", 0.15, listOf("Animation", "Art style", "Cinematography", "Backgrounds", "Effects", "Character design")),
    CategorySpec("worldbuilding", "Worldbuilding", 0.05, listOf("Setting", "Lore", "Power system", "Scope", "Culture", "Immersion"))
)

data class CategorySpec(
    val key: String,
    val title: String,
    val weight: Double,
    val hints: List<String>
)
