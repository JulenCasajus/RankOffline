package org.rankoffline.app

import kotlinx.serialization.Serializable

/** Personal backups are NOT .pack archives and never include catalogues, covers or cached summaries. */
@Serializable
data class PersonalDataBackup(
    val schemaVersion: Int = PERSONAL_BACKUP_SCHEMA_VERSION,
    val source: String = PERSONAL_BACKUP_SOURCE,
    /** 0 = entire app, otherwise the stable physical PackId. */
    val scopeId: Long = ViewingScope.ALL_ID,
    val exportedAt: Long,
    val global: BackupGlobalPreferences? = null,
    val packs: List<BackupLocalPack>,
    val ratings: List<BackupRating>,
    val groupRatings: List<BackupGroupRating>
)

@Serializable
data class BackupGlobalPreferences(
    val allViewSettings: AllViewSettings,
    val themeSelection: String,
    val themes: BackupThemeWorkspace,
    val languageTag: String,
    val appIcon: String,
    val activePackId: Long? = null,
    val lastViewingScopeId: Long = ViewingScope.ALL_ID,
    val lastTab: String = AppTab.SEARCH.name
)

/** An absent file is represented as null; a pack may be uninstalled yet retain local settings. */
@Serializable
data class BackupLocalPack(
    val packId: Long,
    val preferences: BackupPackPreferences? = null,
    val rating: BackupRatingConfiguration? = null
)

@Serializable
data class BackupPackPreferences(
    val showImages: Boolean? = null,
    val titlesOnPodium: Boolean? = null,
    val stickyScore: Boolean? = null,
    val mergeEnabled: Boolean? = null,
    val elaborateRatingEnabled: Boolean? = null,
    val personalTasteEnabled: Boolean? = null,
    val catalogSortOrder: CatalogSortOrder? = null
)

@Serializable
data class BackupRatingConfiguration(
    val categories: List<RatingCategory>,
    val profiles: List<RatingProfile>
) {
    fun toDefinition() = PackRatingDefinition(RatingSystemConfig(categories), RatingProfilesConfig(profiles))
}

@Serializable
data class BackupThemeWorkspace(
    val custom: List<BackupPalette> = emptyList(),
    val builtInOverrides: Map<String, BackupPalette> = emptyMap(),
    val hiddenBuiltIns: Set<String> = emptySet()
)

@Serializable
data class BackupPalette(
    val id: String,
    val name: String,
    val background: Long,
    val surface: Long,
    val primary: Long,
    val secondary: Long
)

@Serializable
data class BackupCategoryScore(
    val categoryId: String,
    val score: Double?,
    val applicable: Boolean
)

@Serializable
data class BackupRating(
    val packId: Long,
    val canonicalId: String,
    val additiveScore: Double,
    val qualityScore: Double?,
    val finalScore: Double?,
    val notes: String,
    val status: String,
    val updatedAt: Long,
    val profileId: String?,
    val categoryScores: List<BackupCategoryScore>
)

@Serializable
data class BackupGroupRating(
    val packId: Long,
    val groupId: String,
    val additiveScore: Double,
    val qualityScore: Double?,
    val finalScore: Double?,
    val notes: String,
    val status: String,
    val updatedAt: Long,
    val profileId: String?,
    val categoryScores: List<BackupCategoryScore>
)

@Serializable
enum class PersonalDataImportMode { MERGE_NEWER, REPLACE }

const val PERSONAL_BACKUP_SCHEMA_VERSION = 2
const val PERSONAL_BACKUP_SOURCE = "rankoffline"
