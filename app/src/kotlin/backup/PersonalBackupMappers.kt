package org.rankoffline.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb


internal fun PersonalDataBackup.toPersonalRestoreData() = PersonalRestoreData(
    ratings = ratings.map { r ->
        MediaRatingEntity(r.packId, r.canonicalId, r.additiveScore, r.qualityScore,
            r.finalScore, r.notes, r.status, r.updatedAt, r.profileId)
    },
    ratingScores = ratings.flatMap { rating ->
        rating.categoryScores.map { score ->
            MediaRatingScoreEntity(rating.packId, rating.canonicalId,
                score.categoryId, score.score, score.applicable)
        }
    },
    groupRatings = groupRatings.map { r ->
        MediaGroupRatingEntity(r.packId, r.groupId, r.additiveScore,
            r.qualityScore, r.finalScore, r.notes, r.status, r.updatedAt, r.profileId)
    },
    groupRatingScores = groupRatings.flatMap { rating ->
        rating.categoryScores.map { score ->
            MediaGroupRatingScoreEntity(rating.packId, rating.groupId,
                score.categoryId, score.score, score.applicable)
        }
    }
)

internal fun AppSettingsState.asBackupGlobal(): BackupGlobalPreferences = BackupGlobalPreferences(
    allViewSettings = allViewSettings,
    themeSelection = themeSelection.persistedValue,
    themes = themeWorkspace().toBackup(),
    languageTag = language.languageTag,
    appIcon = appIcon.persistedValue,
    activePackId = activePackId?.value,
    lastViewingScopeId = lastViewingScope.storedId(),
    lastTab = lastTab.name
)

internal fun ThemeWorkspace.toBackup() = BackupThemeWorkspace(
    custom = customPalettes.map(CustomAppPalette::toBackup),
    builtInOverrides = builtInOverrides.mapKeys { it.key.persistedValue }
        .mapValues { it.value.toBackup() },
    hiddenBuiltIns = hiddenBuiltIns.mapTo(linkedSetOf()) { it.persistedValue }
)

internal fun CustomAppPalette.toBackup() = BackupPalette(
    id, name,
    palette.background.toArgb().toLong() and 0xFFFF_FFFFL,
    palette.surface.toArgb().toLong() and 0xFFFF_FFFFL,
    palette.primary.toArgb().toLong() and 0xFFFF_FFFFL,
    palette.secondary.toArgb().toLong() and 0xFFFF_FFFFL
)

internal fun BackupThemeWorkspace.toWorkspace() = ThemeWorkspace(
    customPalettes = custom.map(BackupPalette::toCustomPalette),
    builtInOverrides = builtInOverrides.mapNotNull { (key, palette) ->
        AppThemePalette.entries.firstOrNull { it.persistedValue == key }
            ?.let { it to palette.toCustomPalette() }
    }.toMap(),
    hiddenBuiltIns = hiddenBuiltIns.mapNotNullTo(linkedSetOf()) { key ->
        AppThemePalette.entries.firstOrNull { it.persistedValue == key }
    }
)

internal fun BackupPalette.toCustomPalette() = CustomAppPalette(
    id, name, AppPalette(Color(background), Color(surface), Color(primary), Color(secondary))
)

/** Uses the existing SettingsRepository API, not a second DataStore instance. */
internal suspend fun SettingsRepository.applyBackupGlobal(saved: BackupGlobalPreferences) {
    applyImportedPersonalSettings(
        imported = emptyMap(),
        importedWorkspace = saved.themes.toWorkspace(),
        importedSortOrders = emptyMap(),
        importedAllViewSettings = saved.allViewSettings,
        importedThemeSelection = saved.themeSelection,
        replace = true
    )
    updateLastViewingScope(ViewingScope.fromStored(saved.lastViewingScopeId))
    updateLastTab(AppTab.valueOf(saved.lastTab))
    updateLanguage(SupportedLanguage.fromLanguageTag(saved.languageTag))
    updateAppIcon(AppIcon.fromPersistedValue(saved.appIcon))
    // Cannot select a pack whose manifest has not been registered in the current process.
    val id = saved.activePackId?.let(::PackId)
    if (id != null && id in state.value.mediaSettings) updateActivePack(id)
}
