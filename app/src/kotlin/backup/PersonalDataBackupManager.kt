package org.rankoffline.app

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Exports and restores personal data only. Neither packs nor downloaded images are backed up. */
class PersonalDataBackupManager internal constructor(
    context: Context,
    private val database: RankOfflineDatabase,
    private val settingsRepository: SettingsRepository,
    private val packManager: PackManager,
    private val onRestoreCheckpoint: suspend (PersonalRestoreCheckpoint) -> Unit = {}
) {
    private val context = context.applicationContext
    private val dao = database.personalDataDao()
    private val metadata = database.mediaCatalogDao()
    private val files = PersonalLocalBackupFiles(context)
    private val journal = PersonalRestoreJournalStore(context)
    private val operationLock = Mutex()

    fun hasPendingRecovery(): Boolean = journal.exists()

    suspend fun export(uri: Uri, scope: ViewingScope = ViewingScope.All) = operationLock.withLock {
        withContext(Dispatchers.IO) {
            val bytes = createBackupBytes(scope)
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Cannot open backup destination")
            stream.buffered().use { it.write(bytes) }
        }
    }

    suspend fun exportForSharing(scope: ViewingScope = ViewingScope.All): File = operationLock.withLock {
        withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, SHARED_BACKUP_DIRECTORY)
            rejectSymlink(folder)
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create share directory")
            folder.listFiles().orEmpty().filter {
                System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1000
            }.forEach { if (!it.isDirectory) it.delete() }
            val result = File(folder, "RankOffline-personal-${UUID.randomUUID()}.json")
            val atomic = android.util.AtomicFile(result)
            val bytes = createBackupBytes(scope)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (failure: Throwable) {
                atomic.failWrite(output)
                throw failure
            }
            result
        }
    }

    private suspend fun createBackupBytes(scope: ViewingScope): ByteArray {
        settingsRepository.loadInitialState()
        // Room is consistent within its own transaction. Local files + DataStore have separate
        // stores, so the UI should not edit settings while a backup is exported.
        val room = database.withTransaction {
            RoomPersonalSnapshot(dao.allRatings(), dao.allRatingScores(),
                dao.allGroupRatings(), dao.allGroupRatingScores())
        }
        val selectedId = (scope as? ViewingScope.Pack)?.packId?.value
        val byItem = room.ratingScores.groupBy { it.packId to it.mediaId }
        val byGroup = room.groupRatingScores.groupBy { it.packId to it.groupId }
        val backup = PersonalDataBackup(
            scopeId = scope.storedId(),
            exportedAt = System.currentTimeMillis(),
            global = if (scope == ViewingScope.All) settingsRepository.state.value.asBackupGlobal() else null,
            packs = files.readAll().filter { selectedId == null || it.packId == selectedId },
            ratings = room.ratings.filter { selectedId == null || it.packId == selectedId }.map { item ->
                BackupRating(item.packId, item.mediaId, item.additiveScore,
                    item.qualityScore, item.finalScore, item.notes, item.status,
                    item.updatedAt, item.profileId,
                    byItem[item.packId to item.mediaId].orEmpty().map {
                        BackupCategoryScore(it.categoryId, it.score, it.applicable)
                    })
            },
            groupRatings = room.groupRatings.filter { selectedId == null || it.packId == selectedId }.map { group ->
                BackupGroupRating(group.packId, group.groupId, group.additiveScore,
                    group.qualityScore, group.finalScore, group.notes, group.status,
                    group.updatedAt, group.profileId,
                    byGroup[group.packId to group.groupId].orEmpty().map {
                        BackupCategoryScore(it.categoryId, it.score, it.applicable)
                    })
            }
        )
        return encodePersonalDataBackup(backup).toByteArray(Charsets.UTF_8)
    }

    suspend fun preview(uri: Uri): PersonalBackupPreview = operationLock.withLock {
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open backup source")
            val backup = stream.use { it.readLimited(MAX_BACKUP_BYTES).decodeToString() }
                .let(::decodePersonalDataBackup)
            val referenced = (backup.packs.map { it.packId } +
                backup.ratings.map { it.packId } + backup.groupRatings.map { it.packId }).toSet()
            val absent = referenced.filterTo(linkedSetOf()) { id ->
                packManager.installedDirectory(PackId(id)) == null
            }
            PersonalBackupPreview(backup, backup.ratings.size, backup.groupRatings.size,
                referenced, absent)
        }
    }

    suspend fun import(preview: PersonalBackupPreview, mode: PersonalDataImportMode,
        scope: ViewingScope = ViewingScope.All) =
        operationLock.withLock {
            settingsRepository.loadInitialState()
            recoverLocked()
            val source = preview.backup.validated()
            val targetId = (scope as? ViewingScope.Pack)?.packId?.value
            require(source.scopeId == ViewingScope.ALL_ID || source.scopeId == scope.storedId()) {
                "Cannot import a single-pack backup into another scope"
            }
            require(targetId != null || source.scopeId == ViewingScope.ALL_ID) {
                "A pack-only backup cannot replace the whole application"
            }
            if (targetId != null) require(
                source.packs.any { it.packId == targetId } ||
                    source.ratings.any { it.packId == targetId } ||
                    source.groupRatings.any { it.packId == targetId }
            ) { "Backup has no data for the selected pack" }
            val backup = source.copy(
                packs = source.packs.filter { targetId == null || it.packId == targetId },
                ratings = source.ratings.filter { targetId == null || it.packId == targetId },
                groupRatings = source.groupRatings.filter { targetId == null || it.packId == targetId }
            )
            val currentPacks = withContext(Dispatchers.IO) { files.readAll() }
            val currentGlobal = settingsRepository.state.value.asBackupGlobal()
            val afterPacks = if (mode == PersonalDataImportMode.REPLACE) {
                if (targetId == null) backup.packs else
                    currentPacks.filterNot { it.packId == targetId } + backup.packs
            } else {
                val currentIds = currentPacks.mapTo(hashSetOf()) { it.packId }
                currentPacks + backup.packs.filterNot { it.packId in currentIds }
            }
            val afterGlobal = if (mode == PersonalDataImportMode.REPLACE && targetId == null)
                requireNotNull(backup.global) else currentGlobal
            val restore = PersonalRestoreJournal(
                operationId = UUID.randomUUID().toString(),
                mode = mode,
                beforeGlobal = currentGlobal,
                afterGlobal = afterGlobal,
                beforePacks = currentPacks,
                afterPacks = afterPacks
            )
            withContext(Dispatchers.IO) { journal.write(restore) }
            onRestoreCheckpoint(PersonalRestoreCheckpoint.JOURNAL_WRITTEN)
            try {
                applySnapshot(restore, committed = true)
                onRestoreCheckpoint(PersonalRestoreCheckpoint.SETTINGS_APPLIED)
                database.withTransaction {
                    dao.restore(backup.toPersonalRestoreData(), mode == PersonalDataImportMode.REPLACE, targetId)
                    metadata.putMetadata(listOf(MediaCatalogMetadataEntity(
                        RESTORE_NAMESPACE, RESTORE_COMMIT_KEY, restore.operationId
                    )))
                }
                onRestoreCheckpoint(PersonalRestoreCheckpoint.ROOM_COMMITTED)
                // Cleanup failure must not roll back committed Room data.
                finishJournalSafely()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { recoverLocked() }
                throw cancelled
            } catch (failure: Exception) {
                withContext(NonCancellable) { recoverLocked() }
                throw failure
            }
        }

    suspend fun recoverInterruptedImport(): Boolean = operationLock.withLock {
        settingsRepository.loadInitialState()
        recoverLocked()
    }

    /** Marker and ratings are in the same Room transaction. Restore only local/global side. */
    private suspend fun recoverLocked(): Boolean {
        if (!journal.exists()) return false
        val record = withContext(Dispatchers.IO) { journal.read() }
        val committed = metadata.metadata(RESTORE_NAMESPACE, RESTORE_COMMIT_KEY) == record.operationId
        applySnapshot(record, committed)
        finishJournalSafely()
        return committed
    }

    private suspend fun applySnapshot(record: PersonalRestoreJournal, committed: Boolean) {
        val targetPacks = if (committed) record.afterPacks else record.beforePacks
        val targetGlobal = if (committed) record.afterGlobal else record.beforeGlobal
        val ids = (record.beforePacks.map { PackId(it.packId) } +
            record.afterPacks.map { PackId(it.packId) }).toSet()
        withContext(Dispatchers.IO) { files.apply(targetPacks, ids) }
        if (settingsRepository.state.value.asBackupGlobal() != targetGlobal) {
            settingsRepository.applyBackupGlobal(targetGlobal)
        }
        // Files may be restored for a pack already loaded in memory this process.
        val installed = settingsRepository.state.value.mediaSettings.keys
        ids.intersect(installed).forEach { id ->
            settingsRepository.reloadPackPreferences(id)
            if (settingsRepository.state.value.mediaSettings[id]?.ratingConfig?.categories?.isNotEmpty() == true) {
                settingsRepository.reloadRatingConfiguration(id)
            }
        }
    }

    private suspend fun finishJournalSafely() {
        try {
            withContext(Dispatchers.IO) { journal.delete() }
            metadata.deleteMetadata(RESTORE_NAMESPACE, RESTORE_COMMIT_KEY)
        } catch (failure: Exception) {
            // Leave marker/journal when possible; recovery remains idempotent.
            Log.w(TAG, "Restore committed but cleanup needs retry", failure)
        }
    }

    private companion object {
        // 0 is never a physical pack ID; reserved exclusively for app-internal metadata here.
        const val RESTORE_NAMESPACE = ViewingScope.ALL_ID
        const val RESTORE_COMMIT_KEY = "personal_restore_commit"
        const val TAG = "RankOfflineBackup"
    }
}

internal enum class PersonalRestoreCheckpoint { JOURNAL_WRITTEN, SETTINGS_APPLIED, ROOM_COMMITTED }

private data class RoomPersonalSnapshot(
    val ratings: List<MediaRatingEntity>,
    val ratingScores: List<MediaRatingScoreEntity>,
    val groupRatings: List<MediaGroupRatingEntity>,
    val groupRatingScores: List<MediaGroupRatingScoreEntity>
)

