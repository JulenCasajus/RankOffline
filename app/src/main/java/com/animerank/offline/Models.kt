package com.animerank.offline

data class Anime(
    val id: String,
    val title: String,
    val malId: Long? = null,
    val anilistId: Long? = null,
    val finalScore: Double? = null,
    val baseScore: Double? = null,
    val personalTaste: Double? = null,
    val status: String? = null
)

data class CategorySpec(
    val key: String,
    val title: String,
    val weight: Double,
    val hints: List<String>
)

val categorySpecs = listOf(
    CategorySpec("writing", "Writing", 0.35, listOf("Story", "Pacing", "Themes", "Dialogue", "Consistency", "Ending")),
    CategorySpec("characters", "Characters", 0.25, listOf("Main cast", "Side cast", "Antagonists", "Development", "Depth", "Dynamics")),
    CategorySpec("engagement", "Engagement", 0.20, listOf("Emotional impact", "Entertainment value", "Viewer investment", "Tension", "Atmosphere", "Memorability")),
    CategorySpec("visuals", "Visuals", 0.15, listOf("Animation", "Art style", "Cinematography", "Backgrounds", "Effects", "Character design")),
    CategorySpec("worldbuilding", "Worldbuilding", 0.05, listOf("Setting", "Lore", "Power system", "Scope", "Culture", "Immersion"))
)

data class RatingDraft(
    val categoryScores: Map<String, Double?> = categorySpecs.associate { it.key to 5.0 },
    val personalTaste: Double = 0.0,
    val notes: String = "",
    val status: String = "Completed"
) {
    fun baseScore(): Double? {
        val applicable = categorySpecs.filter { categoryScores[it.key] != null }
        val totalWeight = applicable.sumOf { it.weight }
        if (totalWeight == 0.0) return null
        return applicable.sumOf { (categoryScores[it.key] ?: 0.0) * it.weight } / totalWeight
    }

    fun finalScore(): Double? = baseScore()?.let { minOf(10.0, it + personalTaste) }
}
