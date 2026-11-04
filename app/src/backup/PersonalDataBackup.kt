package org.rankoffline.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

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
    val stickyScore: Boolean? = null,
    val mergeEnabled: Boolean? = null,
    val useGroupCoversForItems: Boolean? = null,
    val elaborateRatingEnabled: Boolean? = null,
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

data class PersonalBackupPreview(
    val backup: PersonalDataBackup,
    val ratingCount: Int,
    val groupRatingCount: Int,
    val packIds: Set<Long>,
    val missingPackIds: Set<Long>
)

enum class PersonalDataTransferOperation { IDLE, EXPORTING, VALIDATING_IMPORT, IMPORTING, SHARING }
data class PersonalDataTransferState(
    val operation: PersonalDataTransferOperation = PersonalDataTransferOperation.IDLE
) { val active: Boolean get() = operation != PersonalDataTransferOperation.IDLE }

internal class PersonalDataTransferCoordinator {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(PersonalDataTransferState())
    val state: kotlinx.coroutines.flow.StateFlow<PersonalDataTransferState> = _state

    suspend fun run(operation: PersonalDataTransferOperation, block: suspend () -> Unit): Boolean {
        if (!mutex.tryLock()) return false
        _state.value = PersonalDataTransferState(operation)
        return try { block(); true } finally {
            _state.value = PersonalDataTransferState()
            mutex.unlock()
        }
    }
}

@Serializable
enum class PersonalDataImportMode { MERGE_NEWER, REPLACE }

const val PERSONAL_BACKUP_SCHEMA_VERSION = 2
const val PERSONAL_BACKUP_SOURCE = "rankoffline"
internal const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
internal const val SHARED_BACKUP_DIRECTORY = "shared-personal-backups"

internal val personalBackupJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }

internal fun encodePersonalDataBackup(backup: PersonalDataBackup): String =
    personalBackupJson.encodeToString(backup.validated()).also {
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "Backup too large" }
    }

internal fun decodePersonalDataBackup(raw: String): PersonalDataBackup {
    require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "Backup too large" }
    return personalBackupJson.decodeFromString<PersonalDataBackup>(raw).validated()
}

internal fun PersonalDataBackup.validated(): PersonalDataBackup = apply {
    require(schemaVersion == PERSONAL_BACKUP_SCHEMA_VERSION) { "Unsupported personal backup format" }
    require(source == PERSONAL_BACKUP_SOURCE && exportedAt >= 0)
    if (scopeId == ViewingScope.ALL_ID) requireNotNull(global) { "Global backup must carry app settings" }
    else {
        PackId(scopeId)
        require(global == null) { "A pack-only backup cannot alter global settings" }
        require(packs.all { it.packId == scopeId } && ratings.all { it.packId == scopeId } &&
            groupRatings.all { it.packId == scopeId }) { "Backup contains data outside selected pack" }
    }
    require(packs.size <= 500 && packs.map { it.packId }.distinct().size == packs.size)
    require(ratings.size + groupRatings.size <= 100_000)
    require(ratings.sumOf { it.categoryScores.size.toLong() } +
        groupRatings.sumOf { it.categoryScores.size.toLong() } <= 1_000_000L)
    require(ratings.distinctBy { it.packId to it.canonicalId }.size == ratings.size)
    require(groupRatings.distinctBy { it.packId to it.groupId }.size == groupRatings.size)
    packs.forEach { pack ->
        PackId(pack.packId)
        pack.rating?.let { validateRatingConfiguration(it.toDefinition().config, it.toDefinition().profiles) }
        // PersonalPackPreferences is enum-only and nullable, no user-controlled paths.
    }
    (ratings.map { it.packId } + groupRatings.map { it.packId }).forEach { PackId(it) }
    ratings.forEach { it.validate() }
    groupRatings.forEach { it.validate() }
    global?.let { settings ->
        settings.activePackId?.let(::PackId)
        require(settings.lastViewingScopeId == ViewingScope.ALL_ID ||
            runCatching { PackId(settings.lastViewingScopeId) }.isSuccess)
        require(AppTab.entries.any { it.name == settings.lastTab })
        require(settings.themeSelection.length <= 200 && settings.languageTag.length <= 64)
        require(AppIcon.entries.any { it.persistedValue == settings.appIcon })
        require(settings.themes.custom.size + settings.themes.builtInOverrides.size <= 200)
        require(settings.themes.custom.distinctBy { it.id }.size == settings.themes.custom.size)
        settings.themes.custom.forEach { it.validate() }
        settings.themes.builtInOverrides.forEach { (id, value) ->
            require(AppThemePalette.entries.any { it.persistedValue == id }); value.validate()
        }
        require(settings.themes.hiddenBuiltIns.all { id ->
            AppThemePalette.entries.any { it.persistedValue == id }
        })
        require(AppThemePalette.entries.any { it.persistedValue == settings.themeSelection } ||
            (settings.themeSelection.startsWith("custom:") &&
                settings.themes.custom.any { it.id == settings.themeSelection.removePrefix("custom:") }))
    }
}

private fun BackupRating.validate() {
    validateRatingFields(packId, canonicalId, additiveScore, qualityScore, finalScore,
        notes, status, updatedAt, profileId, categoryScores)
}

private fun BackupGroupRating.validate() {
    validateRatingFields(packId, groupId, additiveScore, qualityScore, finalScore,
        notes, status, updatedAt, profileId, categoryScores)
}

private fun validateRatingFields(
    packId: Long, id: String, bonus: Double, quality: Double?, final: Double?,
    notes: String, status: String, updatedAt: Long, profile: String?,
    scores: List<BackupCategoryScore>
) {
    PackId(packId)
    require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,191}")))
    require(bonus.isFinite() && bonus in BONUS_MIN..BONUS_MAX)
    require(listOfNotNull(quality, final).all { it.isFinite() && it in RATING_MIN..RATING_MAX })
    require(notes.length <= 100_000 && status.length <= 100 && updatedAt >= 0)
    require(profile == null || profile.matches(Regex("[a-z][a-z0-9_]{0,63}")))
    require(scores.size <= 48 && scores.distinctBy { it.categoryId }.size == scores.size)
    scores.forEach {
        require(it.categoryId.matches(Regex("[a-z][a-z0-9_]{0,63}")))
        require(it.applicable == (it.score != null))
        require(it.score == null || it.score.isFinite() && it.score in RATING_MIN..RATING_MAX)
    }
}

private fun BackupPalette.validate() {
    require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
    require(name.isNotBlank() && name.length <= MAX_CUSTOM_THEME_NAME_LENGTH)
    require(listOf(background, surface, primary, secondary).all { it in 0..0xFFFF_FFFFL })
}
