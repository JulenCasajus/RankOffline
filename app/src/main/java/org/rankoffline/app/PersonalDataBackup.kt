package org.rankoffline.app

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.room.withTransaction
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PersonalDataBackup(
    val schemaVersion: Int = PERSONAL_BACKUP_SCHEMA_VERSION,
    val source: String = PERSONAL_BACKUP_SOURCE,
    val exportedAt: Long,
    val mediaSettings: Map<String, MediaSettings>,
    val initializedPackIds: Set<String>,
    val catalogSortOrders: Map<String, String>,
    val allViewSettings: AllViewSettings,
    val themeSelection: String,
    val themes: BackupThemeWorkspace,
    val ratings: List<BackupRating>,
    val groupRatings: List<BackupGroupRating>,
    /** Explicit ownership of every pack-scoped value in this backup. Empty means legacy backup. */
    val scopePackIds: Set<String> = emptySet(),
    /** Legacy full-app backups included palettes and other global settings. New scoped exports do not. */
    val globalSettingsIncluded: Boolean = true
)

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
data class BackupRating(
    val packId: String,
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
    val packId: String,
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
data class BackupCategoryScore(
    val categoryId: String,
    val score: Double?,
    val applicable: Boolean
)

data class PersonalBackupPreview(
    val backup: PersonalDataBackup,
    val ratingCount: Int,
    val groupRatingCount: Int,
    val packIds: Set<String>,
    val missingPackIds: Set<String>
)

enum class PersonalDataTransferOperation {
    IDLE,
    EXPORTING,
    VALIDATING_IMPORT,
    IMPORTING,
    SHARING
}

data class PersonalDataTransferState(
    val operation: PersonalDataTransferOperation = PersonalDataTransferOperation.IDLE
) {
    val active: Boolean get() = operation != PersonalDataTransferOperation.IDLE
}

internal class PersonalDataTransferCoordinator {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(PersonalDataTransferState())
    val state: StateFlow<PersonalDataTransferState> = _state.asStateFlow()

    suspend fun run(operation: PersonalDataTransferOperation, block: suspend () -> Unit): Boolean {
        if (!mutex.tryLock()) return false
        _state.value = PersonalDataTransferState(operation)
        return try {
            block()
            true
        } finally {
            _state.value = PersonalDataTransferState()
            mutex.unlock()
        }
    }
}

@Serializable
enum class PersonalDataImportMode { MERGE_NEWER, REPLACE }

@Serializable
private data class BackupSettingsSnapshot(
    val mediaSettings: Map<String, MediaSettings>,
    val initializedPackIds: Set<String>,
    val catalogSortOrders: Map<String, String>,
    val allViewSettings: AllViewSettings,
    val themeSelection: String,
    val themes: BackupThemeWorkspace,
    val scopePackIds: Set<String> = emptySet(),
    val globalSettingsIncluded: Boolean = true
)

@Serializable
private data class PersonalRestoreJournal(
    val schemaVersion: Int = RESTORE_JOURNAL_SCHEMA_VERSION,
    val operationId: String,
    val mode: PersonalDataImportMode,
    val previousSettings: BackupSettingsSnapshot,
    val importedSettings: BackupSettingsSnapshot
)

internal enum class PersonalRestoreCheckpoint {
    JOURNAL_WRITTEN,
    SETTINGS_APPLIED,
    ROOM_COMMITTED
}

private val personalBackupJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}

internal fun encodePersonalDataBackup(value: PersonalDataBackup): String =
    personalBackupJson.encodeToString(value.validated())

internal fun decodePersonalDataBackup(value: String): PersonalDataBackup =
    personalBackupJson.decodeFromString<PersonalDataBackup>(value).validated()

class PersonalDataBackupManager internal constructor(
    context: Context,
    private val database: AnimeDatabase,
    private val settingsRepository: SettingsRepository,
    private val packManager: CategoryPackManager,
    private val onRestoreCheckpoint: suspend (PersonalRestoreCheckpoint) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val dao = database.personalDataDao()
    private val catalogDao = database.mediaCatalogDao()
    private val restoreMutex = Mutex()
    private val restoreJournal = File(appContext.filesDir, RESTORE_JOURNAL_FILE)
    private val restoreJournalBackup = File(appContext.filesDir, "$RESTORE_JOURNAL_FILE.backup")

    fun hasPendingRecovery(): Boolean = restoreJournal.isFile || restoreJournalBackup.isFile

    suspend fun export(uri: Uri, scopePackIds: Set<PackId>) = withContext(Dispatchers.IO) {
        val bytes = createBackupBytes(scopePackIds.mapTo(linkedSetOf(), PackId::value))
        val output = appContext.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Unable to open backup destination")
        output.buffered().use { it.write(bytes) }
    }

    suspend fun exportForSharing(scopePackIds: Set<PackId>? = null): File = withContext(Dispatchers.IO) {
        val directory = File(appContext.cacheDir, SHARED_BACKUP_DIRECTORY)
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create share directory" }
        directory.listFiles().orEmpty()
            .filter { System.currentTimeMillis() - it.lastModified() > SHARED_BACKUP_MAX_AGE_MS }
            .forEach(File::delete)
        val destination = File(directory, "RankOffline-personal-${UUID.randomUUID()}.json")
        val temporary = File(directory, ".${destination.name}.part")
        temporary.outputStream().buffered().use {
            it.write(createBackupBytes(scopePackIds?.mapTo(linkedSetOf(), PackId::value)))
        }
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        destination
    }

    private suspend fun createBackupBytes(requestedScope: Set<String>?): ByteArray {
        val room = database.withTransaction {
            ExportRoomSnapshot(
                ratings = dao.allRatings(),
                ratingScores = dao.allRatingScores(),
                groupRatings = dao.allGroupRatings(),
                groupRatingScores = dao.allGroupRatingScores()
            )
        }
        // Room is internally consistent; DataStore is sampled immediately afterwards because the
        // two stores cannot share a transaction.
        val state = settingsRepository.state.value
        val includeGlobalSettings = requestedScope == null
        val scope = requestedScope ?: (
            room.ratings.map(MediaRatingEntity::packId) +
                room.groupRatings.map(MediaGroupRatingEntity::packId) +
                state.mediaSettings.keys.map { it.toPackId().value }
            ).toSet()
        require(scope.isNotEmpty()) { "Backup scope must contain at least one pack" }
        scope.forEach(::PackId)
        val scopedRatings = room.ratings.filter { it.packId in scope }
        val scopedGroupRatings = room.groupRatings.filter { it.packId in scope }
        val ratingScores = room.ratingScores
            .filter { it.packId in scope }
            .groupBy { it.packId to it.mediaId }
        val groupScores = room.groupRatingScores
            .filter { it.packId in scope }
            .groupBy { it.packId to it.groupId }
        val scopedMediaSettings = state.mediaSettings.filterKeys { it.toPackId().value in scope }
        val scopedSortOrders = state.catalogSortOrders.filterKeys { it.toPackId().value in scope }
        val backup = PersonalDataBackup(
            exportedAt = System.currentTimeMillis(),
            mediaSettings = scopedMediaSettings.mapKeys { it.key.toPackId().value },
            initializedPackIds = settingsRepository.initializedPackSettingsSnapshot()
                .map { it.toPackId().value }
                .filterTo(linkedSetOf()) { it in scope },
            catalogSortOrders = scopedSortOrders.mapKeys { it.key.toPackId().value }
                .mapValues { it.value.name },
            allViewSettings = if (includeGlobalSettings) state.allViewSettings else AllViewSettings(),
            themeSelection = if (includeGlobalSettings) state.themeSelection.persistedValue
            else AppThemePalette.MIDNIGHT_NEON.persistedValue,
            themes = if (includeGlobalSettings) state.themeWorkspace().toBackup()
            else BackupThemeWorkspace(),
            ratings = scopedRatings.map { rating ->
                BackupRating(
                    rating.packId,
                    rating.mediaId,
                    rating.additiveScore,
                    rating.qualityScore,
                    rating.finalScore,
                    rating.notes,
                    rating.status,
                    rating.updatedAt,
                    rating.profileId,
                    ratingScores[rating.packId to rating.mediaId].orEmpty().map {
                        BackupCategoryScore(it.categoryId, it.score, it.applicable)
                    }
                )
            },
            groupRatings = scopedGroupRatings.map { rating ->
                BackupGroupRating(
                    rating.packId,
                    rating.groupId,
                    rating.additiveScore,
                    rating.qualityScore,
                    rating.finalScore,
                    rating.notes,
                    rating.status,
                    rating.updatedAt,
                    rating.profileId,
                    groupScores[rating.packId to rating.groupId].orEmpty().map {
                        BackupCategoryScore(it.categoryId, it.score, it.applicable)
                    }
                )
            },
            scopePackIds = if (includeGlobalSettings) emptySet() else scope,
            globalSettingsIncluded = includeGlobalSettings
        )
        val bytes = encodePersonalDataBackup(backup).encodeToByteArray()
        requireValidBackupSize(bytes.size)
        return bytes
    }

    suspend fun preview(uri: Uri, allowedPackIds: Set<PackId>? = null): PersonalBackupPreview = withContext(Dispatchers.IO) {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open backup")
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                requireValidBackupSize(total)
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        requireValidBackupSize(bytes.size)
        val decoded = decodePersonalDataBackup(bytes.decodeToString())
        val sourceScope = decoded.effectiveScopePackIds()
        val allowedScope = allowedPackIds?.mapTo(linkedSetOf(), PackId::value)
        if (allowedScope != null) {
            require(sourceScope.isNotEmpty() && sourceScope.all { it in allowedScope }) {
                "Backup contains data outside the selected package scope"
            }
        }
        // Package settings never import app-global palettes/theme state. Legacy backups remain
        // readable, but only their explicitly owned pack data enters this flow.
        val backup = decoded.scopedTo(sourceScope)
        val packIds = backup.effectiveScopePackIds()
        val installed = MediaType.entries.filterTo(hashSetOf()) {
            packManager.installedDirectory(it) != null
        }.mapTo(hashSetOf()) { it.toPackId().value }
        PersonalBackupPreview(
            backup,
            backup.ratings.size,
            backup.groupRatings.size,
            packIds,
            packIds - installed
        )
    }

    suspend fun import(preview: PersonalBackupPreview, mode: PersonalDataImportMode) =
        restoreMutex.withLock {
            val backup = preview.backup.validated()
            recoverInterruptedImportLocked()
            val journal = PersonalRestoreJournal(
                operationId = UUID.randomUUID().toString(),
                mode = mode,
                previousSettings = settingsRepository.currentBackupSettings(),
                importedSettings = backup.settingsSnapshot()
            )
            writeRestoreJournal(journal)
            onRestoreCheckpoint(PersonalRestoreCheckpoint.JOURNAL_WRITTEN)
            try {
                applySettings(journal.importedSettings, mode == PersonalDataImportMode.REPLACE)
                onRestoreCheckpoint(PersonalRestoreCheckpoint.SETTINGS_APPLIED)
                database.withTransaction {
                    dao.restore(backup.toRestoreData(), mode == PersonalDataImportMode.REPLACE)
                    catalogDao.upsertMetadata(
                        listOf(
                            MediaCatalogMetadataEntity(
                                RESTORE_METADATA_NAMESPACE,
                                RESTORE_METADATA_KEY,
                                journal.operationId
                            )
                        )
                    )
                }
                onRestoreCheckpoint(PersonalRestoreCheckpoint.ROOM_COMMITTED)
                finishRestoreJournal()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { recoverInterruptedImportLocked() }
                throw cancelled
            } catch (exception: Exception) {
                val committed = withContext(NonCancellable) { recoverInterruptedImportLocked() }
                if (!committed) throw exception
            }
        }

    /** Reconciles an import interrupted between the DataStore and Room checkpoints. */
    suspend fun recoverInterruptedImport(): Boolean = restoreMutex.withLock {
        settingsRepository.loadInitialState()
        recoverInterruptedImportLocked()
    }

    private suspend fun recoverInterruptedImportLocked(): Boolean {
        if (!restoreJournal.isFile && !restoreJournalBackup.isFile) {
            catalogDao.deleteMetadata(RESTORE_METADATA_NAMESPACE, RESTORE_METADATA_KEY)
            return false
        }
        val journal = readRestoreJournal()
        require(journal.schemaVersion == RESTORE_JOURNAL_SCHEMA_VERSION)
        val committed = catalogDao.metadata(
            RESTORE_METADATA_NAMESPACE,
            RESTORE_METADATA_KEY
        ) == journal.operationId
        if (committed) {
            applySettings(
                journal.importedSettings,
                journal.mode == PersonalDataImportMode.REPLACE
            )
        } else {
            applySettings(journal.previousSettings, replace = true)
        }
        runCatching { finishRestoreJournal() }.onFailure { exception ->
            // The journal + Room marker deliberately remain a safe, idempotent recovery point.
            // Do not turn an already committed restore into a rollback because cleanup failed.
            Log.w(BACKUP_LOG_TAG, "Restore is consistent but journal cleanup will be retried", exception)
        }
        Log.i(
            BACKUP_LOG_TAG,
            "operation=restore-recovery id=${journal.operationId} committed=$committed"
        )
        return committed
    }

    private suspend fun applySettings(snapshot: BackupSettingsSnapshot, replace: Boolean) {
        val scopedMediaTypes = snapshot.scopePackIds.mapNotNullTo(linkedSetOf()) {
            PackId(it).toOfficialMediaTypeOrNull()
        }
        settingsRepository.applyImportedPersonalSettings(
            imported = snapshot.mediaSettings.mapNotNull { (packId, value) ->
                PackId(packId).toOfficialMediaTypeOrNull()?.let { it to value }
            }.toMap(),
            importedInitializedPacks = snapshot.initializedPackIds.mapNotNullTo(linkedSetOf()) {
                PackId(it).toOfficialMediaTypeOrNull()
            },
            importedWorkspace = snapshot.themes.toDomain(),
            importedSortOrders = snapshot.catalogSortOrders.mapNotNull { (packId, value) ->
                val mediaType = PackId(packId).toOfficialMediaTypeOrNull()
                    ?: return@mapNotNull null
                CatalogSortOrder.entries.firstOrNull { it.name == value }
                    ?.let { mediaType to it }
            }.toMap(),
            importedAllViewSettings = snapshot.allViewSettings,
            importedThemeSelection = snapshot.themeSelection,
            replace = replace,
            scopeMediaTypes = scopedMediaTypes.takeIf { snapshot.scopePackIds.isNotEmpty() },
            includeGlobalSettings = snapshot.globalSettingsIncluded
        )
    }

    private fun writeRestoreJournal(journal: PersonalRestoreJournal) {
        val encoded = personalBackupJson.encodeToString(journal)
        writeAtomically(restoreJournalBackup, encoded)
        writeAtomically(restoreJournal, encoded)
    }

    private fun writeAtomically(destination: File, encoded: String) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(encoded)
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private suspend fun readRestoreJournal(): PersonalRestoreJournal {
        val failures = mutableListOf<Throwable>()
        listOf(restoreJournal, restoreJournalBackup).forEach { file ->
            if (file.isFile) {
                runCatching {
                    personalBackupJson.decodeFromString<PersonalRestoreJournal>(file.readText())
                        .also { require(it.schemaVersion == RESTORE_JOURNAL_SCHEMA_VERSION) }
                }.onSuccess { return it }.onFailure(failures::add)
            }
        }
        val suffix = System.currentTimeMillis().toString()
        listOf(restoreJournal, restoreJournalBackup).forEach { file ->
            if (file.exists()) file.renameTo(File(file.parentFile, "${file.name}.corrupt-$suffix"))
        }
        catalogDao.deleteMetadata(RESTORE_METADATA_NAMESPACE, RESTORE_METADATA_KEY)
        throw IOException("Personal restore journal is unreadable", failures.firstOrNull())
    }

    private suspend fun finishRestoreJournal() {
        if (restoreJournal.exists() && !restoreJournal.delete()) {
            throw IOException("Unable to remove personal restore journal")
        }
        if (restoreJournalBackup.exists() && !restoreJournalBackup.delete()) {
            throw IOException("Unable to remove personal restore journal backup")
        }
        runCatching {
            catalogDao.deleteMetadata(RESTORE_METADATA_NAMESPACE, RESTORE_METADATA_KEY)
        }.onFailure { exception ->
            Log.w(BACKUP_LOG_TAG, "Unable to remove completed restore marker", exception)
        }
    }

}

private data class ExportRoomSnapshot(
    val ratings: List<MediaRatingEntity>,
    val ratingScores: List<MediaRatingScoreEntity>,
    val groupRatings: List<MediaGroupRatingEntity>,
    val groupRatingScores: List<MediaGroupRatingScoreEntity>
)

private fun SettingsRepository.currentBackupSettings(): BackupSettingsSnapshot {
    val current = state.value
    return BackupSettingsSnapshot(
        mediaSettings = current.mediaSettings.mapKeys { it.key.toPackId().value },
        initializedPackIds = initializedPackSettingsSnapshot()
            .mapTo(linkedSetOf()) { it.toPackId().value },
        catalogSortOrders = current.catalogSortOrders.mapKeys { it.key.toPackId().value }
            .mapValues { it.value.name },
        allViewSettings = current.allViewSettings,
        themeSelection = current.themeSelection.persistedValue,
        themes = current.themeWorkspace().toBackup(),
        globalSettingsIncluded = true
    )
}

private fun PersonalDataBackup.settingsSnapshot() = BackupSettingsSnapshot(
    mediaSettings = mediaSettings,
    initializedPackIds = initializedPackIds,
    catalogSortOrders = catalogSortOrders,
    allViewSettings = allViewSettings,
    themeSelection = themeSelection,
    themes = themes,
    scopePackIds = effectiveScopePackIds(),
    globalSettingsIncluded = globalSettingsIncluded
)

private fun PersonalDataBackup.ownedPackIds(): Set<String> = (
    ratings.map(BackupRating::packId) +
        groupRatings.map(BackupGroupRating::packId) +
        mediaSettings.keys +
        initializedPackIds +
        catalogSortOrders.keys
    ).toSet()

private fun PersonalDataBackup.effectiveScopePackIds(): Set<String> =
    scopePackIds.ifEmpty(::ownedPackIds)

private fun PersonalDataBackup.scopedTo(scope: Set<String>): PersonalDataBackup {
    require(scope.isNotEmpty()) { "Backup scope must contain at least one pack" }
    require(scope.all { it in effectiveScopePackIds() }) { "Backup scope mismatch" }
    return copy(
        mediaSettings = mediaSettings.filterKeys { it in scope },
        initializedPackIds = initializedPackIds.filterTo(linkedSetOf()) { it in scope },
        catalogSortOrders = catalogSortOrders.filterKeys { it in scope },
        allViewSettings = AllViewSettings(),
        themeSelection = AppThemePalette.MIDNIGHT_NEON.persistedValue,
        themes = BackupThemeWorkspace(),
        ratings = ratings.filter { it.packId in scope },
        groupRatings = groupRatings.filter { it.packId in scope },
        scopePackIds = scope,
        globalSettingsIncluded = false
    ).validated()
}

private fun PersonalDataBackup.validated(): PersonalDataBackup = apply {
    require(schemaVersion == PERSONAL_BACKUP_SCHEMA_VERSION)
    require(source == PERSONAL_BACKUP_SOURCE)
    require(exportedAt >= 0L)
    require(ratings.size + groupRatings.size <= MAX_RATINGS)
    require(ratings.sumOf { it.categoryScores.size } + groupRatings.sumOf { it.categoryScores.size } <= MAX_SCORES)
    require(ratings.map { it.packId to it.canonicalId }.distinct().size == ratings.size)
    require(groupRatings.map { it.packId to it.groupId }.distinct().size == groupRatings.size)
    require(mediaSettings.size <= MAX_PACK_SETTINGS)
    require(initializedPackIds.size <= MAX_PACK_SETTINGS)
    require(catalogSortOrders.size <= MAX_PACK_SETTINGS)
    require(scopePackIds.size <= MAX_PACK_SETTINGS)
    (ratings.map(BackupRating::packId) + groupRatings.map(BackupGroupRating::packId) +
        mediaSettings.keys + initializedPackIds + catalogSortOrders.keys + scopePackIds).forEach { PackId(it) }
    if (scopePackIds.isNotEmpty()) {
        require(ownedPackIds().all { it in scopePackIds })
    }
    ratings.forEach(BackupRating::validate)
    groupRatings.forEach(BackupGroupRating::validate)
    mediaSettings.values.forEach(MediaSettings::validateForBackup)
    require(catalogSortOrders.values.all { value -> CatalogSortOrder.entries.any { it.name == value } })
    require(themes.custom.size + themes.builtInOverrides.size <= MAX_THEMES)
    require(themes.hiddenBuiltIns.size <= AppThemePalette.entries.size)
    require(themeSelection.length <= 200)
    require(themes.custom.map(BackupPalette::id).distinct().size == themes.custom.size)
    themes.custom.forEach(BackupPalette::validate)
    themes.builtInOverrides.forEach { (builtIn, palette) ->
        require(AppThemePalette.entries.any { it.persistedValue == builtIn })
        palette.validate()
    }
    themes.hiddenBuiltIns.forEach { builtIn ->
        require(AppThemePalette.entries.any { it.persistedValue == builtIn })
    }
    require(
        AppThemePalette.entries.any { it.persistedValue == themeSelection } ||
            (themeSelection.startsWith("custom:") &&
                themes.custom.any { it.id == themeSelection.removePrefix("custom:") })
    )
}

private fun MediaSettings.validateForBackup() {
    val categories = ratingConfig.categories
    require(categories.size <= MAX_RATING_CATEGORIES)
    require(categories.map(RatingCategory::id).distinct().size == categories.size)
    require(categories.map(RatingCategory::order).distinct().size == categories.size)
    categories.forEach { category ->
        require(category.id.isNotBlank() && category.id.length <= 100)
        require(category.name.isNotBlank() && category.name.length <= 200)
        require(category.description.length <= 2_000 && category.order >= 0)
        require(category.weight.isFinite() && category.weight in 0.0..100.0)
        require(category.color.matches(Regex("#[0-9A-Fa-f]{6}")))
    }
    require(categories.none { it.active } || categories.filter { it.active }.sumOf { it.weight } > 0.0)
    require(ratingProfiles.profiles.size <= MAX_RATING_PROFILES)
    require(ratingProfiles.profiles.map(RatingProfile::id).distinct().size == ratingProfiles.profiles.size)
    ratingProfiles.profiles.forEach { profile ->
        require(profile.id.isNotBlank() && profile.id.length <= 100)
        require(profile.name.isNotBlank() && profile.name.length <= 200)
        require(profile.weights.size <= MAX_RATING_CATEGORIES)
        require(profile.weights.keys.all { id -> categories.any { it.id == id } })
        require(profile.weights.values.all { it.isFinite() && it in 0.0..100.0 })
        require(profile.weights.isEmpty() || profile.weights.values.sum() > 0.0)
    }
    ratingConfig.additiveBonus?.let { bonus ->
        require(bonus.name.length <= 200 && bonus.description.length <= 2_000)
        require(bonus.maxValue.isFinite() && bonus.maxValue in BONUS_MIN..BONUS_MAX)
        require(bonus.color.matches(Regex("#[0-9A-Fa-f]{6}")))
    }
}

private fun BackupRating.validate() {
    require(canonicalId.isNotBlank() && canonicalId.length <= MAX_ID_LENGTH)
    require(notes.length <= MAX_NOTES_LENGTH && status.length <= 100)
    require(updatedAt >= 0L)
    require(additiveScore.isFinite() && additiveScore in BONUS_MIN..BONUS_MAX)
    require(listOfNotNull(qualityScore, finalScore).all { it.isFinite() && it in RATING_MIN..RATING_MAX })
    require(profileId == null || profileId.isNotBlank() && profileId.length <= 100)
    categoryScores.forEach(BackupCategoryScore::validate)
    require(categoryScores.map(BackupCategoryScore::categoryId).distinct().size == categoryScores.size)
}

private fun BackupGroupRating.validate() {
    require(groupId.isNotBlank() && groupId.length <= MAX_ID_LENGTH)
    require(notes.length <= MAX_NOTES_LENGTH && status.length <= 100)
    require(updatedAt >= 0L)
    require(additiveScore.isFinite() && additiveScore in BONUS_MIN..BONUS_MAX)
    require(listOfNotNull(qualityScore, finalScore).all { it.isFinite() && it in RATING_MIN..RATING_MAX })
    require(profileId == null || profileId.isNotBlank() && profileId.length <= 100)
    categoryScores.forEach(BackupCategoryScore::validate)
    require(categoryScores.map(BackupCategoryScore::categoryId).distinct().size == categoryScores.size)
}

private fun BackupCategoryScore.validate() {
    require(categoryId.isNotBlank() && categoryId.length <= 100)
    require(score == null || score.isFinite() && score in RATING_MIN..RATING_MAX)
    require(applicable == (score != null))
}

private fun BackupPalette.validate() {
    require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
    require(name.isNotBlank() && name.length <= MAX_CUSTOM_THEME_NAME_LENGTH)
    require(listOf(background, surface, primary, secondary).all { it in 0L..0xFFFF_FFFFL })
}

private fun ThemeWorkspace.toBackup() = BackupThemeWorkspace(
    custom = customPalettes.map(CustomAppPalette::toBackup),
    builtInOverrides = builtInOverrides.mapKeys { it.key.persistedValue }
        .mapValues { it.value.toBackup() },
    hiddenBuiltIns = hiddenBuiltIns.mapTo(linkedSetOf()) { it.persistedValue }
)

private fun CustomAppPalette.toBackup() = BackupPalette(
    id,
    name,
    palette.background.persistedArgb(),
    palette.surface.persistedArgb(),
    palette.primary.persistedArgb(),
    palette.secondary.persistedArgb()
)

private fun BackupThemeWorkspace.toDomain() = ThemeWorkspace(
    customPalettes = custom.map(BackupPalette::toDomain),
    builtInOverrides = builtInOverrides.mapNotNull { (id, palette) ->
        AppThemePalette.entries.firstOrNull { it.persistedValue == id }
            ?.let { it to palette.toDomain() }
    }.toMap(),
    hiddenBuiltIns = hiddenBuiltIns.mapNotNullTo(linkedSetOf()) { id ->
        AppThemePalette.entries.firstOrNull { it.persistedValue == id }
    }
)

private fun BackupPalette.toDomain() = CustomAppPalette(
    id,
    name.trim(),
    AppPalette(Color(background), Color(surface), Color(primary), Color(secondary))
)

private fun Color.persistedArgb(): Long = toArgb().toLong() and 0xFFFF_FFFFL

private fun PersonalDataBackup.toRestoreData() = PersonalRestoreData(
    ratings = ratings.map {
        MediaRatingEntity(
            it.canonicalId, it.additiveScore, it.qualityScore, it.finalScore, it.notes,
            it.status, it.updatedAt, it.profileId, it.packId
        )
    },
    ratingScores = ratings.flatMap { rating ->
        rating.categoryScores.map {
            MediaRatingScoreEntity(rating.canonicalId, it.categoryId, it.score, it.applicable, rating.packId)
        }
    },
    groupRatings = groupRatings.map {
        MediaGroupRatingEntity(
            it.groupId, it.additiveScore, it.qualityScore, it.finalScore, it.notes,
            it.status, it.updatedAt, it.profileId, it.packId
        )
    },
    groupRatingScores = groupRatings.flatMap { rating ->
        rating.categoryScores.map {
            MediaGroupRatingScoreEntity(rating.groupId, it.categoryId, it.score, it.applicable, rating.packId)
        }
    },
    scopePackIds = effectiveScopePackIds()
)

const val PERSONAL_BACKUP_SCHEMA_VERSION = 1
const val PERSONAL_BACKUP_SOURCE = "rankoffline"
internal const val MAX_BACKUP_BYTES = 16 * 1024 * 1024
internal fun requireValidBackupSize(bytes: Int) {
    require(bytes in 0..MAX_BACKUP_BYTES) { "Backup is too large" }
}
private const val RESTORE_JOURNAL_SCHEMA_VERSION = 1
private const val RESTORE_JOURNAL_FILE = "personal-restore-journal-v1.json"
internal const val SHARED_BACKUP_DIRECTORY = "shared-personal-backups"
private const val SHARED_BACKUP_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
private const val RESTORE_METADATA_NAMESPACE = "__rankoffline_internal"
private const val RESTORE_METADATA_KEY = "personal_restore_commit_v1"
private const val BACKUP_LOG_TAG = "RankOfflineBackup"
private const val MAX_RATINGS = 100_000
private const val MAX_SCORES = 1_000_000
private const val MAX_THEMES = 200
private const val MAX_PACK_SETTINGS = 100
private const val MAX_RATING_CATEGORIES = 100
private const val MAX_RATING_PROFILES = 100
private const val MAX_ID_LENGTH = 500
private const val MAX_NOTES_LENGTH = 100_000
