package org.rankoffline.app

import org.json.JSONObject

/** Only pack-specific categories/profiles are legal. Score scale and personal bonus are app rules. */
internal object PackManifestRating {
    fun parse(root: JSONObject, capabilities: PackCapabilities): PackRatingDefinition? {
        if (!capabilities.ratings) {
            require(!root.has("rating") || root.isNull("rating")) { "Ratings disabled for this pack" }
            return null
        }
        require(root.has("rating") && !root.isNull("rating")) { "A ratings-enabled pack must declare rating" }
        val rating = root.getJSONObject("rating")
        rating.requireOnlyKeys("categories", "profiles") // rejects additiveBonus and score ranges
        val categoryJson = rating.getJSONArray("categories")
        require(categoryJson.length() in 1..48) { "Invalid category count" }
        val categories = (0 until categoryJson.length()).map { index ->
            val obj = categoryJson.getJSONObject(index)
            obj.requireOnlyKeys("id", "name", "description", "weight", "color", "order", "active")
            RatingCategory(
                id = obj.getString("id"),
                name = obj.requiredText("name", 160),
                description = obj.optionalText("description", 1024) ?: "",
                weight = obj.getDouble("weight"),
                color = obj.getString("color"),
                order = obj.getInt("order"),
                active = if (obj.has("active")) obj.getBoolean("active") else true
            )
        }
        val profileJson = rating.getJSONArray("profiles")
        require(profileJson.length() in 1..32) { "Invalid profile count" }
        val profiles = (0 until profileJson.length()).map { index ->
            val obj = profileJson.getJSONObject(index)
            obj.requireOnlyKeys("id", "name", "weights")
            val weightsJson = obj.getJSONObject("weights")
            require(weightsJson.length() in 1..48)
            val weights = weightsJson.keys().asSequence().associateWith { weightsJson.getDouble(it) }
            RatingProfile(obj.getString("id"), obj.requiredText("name", 160), weights)
        }
        return PackRatingDefinition(RatingSystemConfig(categories), RatingProfilesConfig(profiles)).also {
            validateRatingConfiguration(it.config, it.profiles)
        }
    }
}
