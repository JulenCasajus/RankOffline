package org.rankoffline.app

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Crash-recovery journal for a restore spanning Room, DataStore and editable local JSON files. */
@Serializable
internal data class PersonalRestoreJournal(
    val operationId: String,
    val mode: PersonalDataImportMode,
    val beforeGlobal: BackupGlobalPreferences,
    val afterGlobal: BackupGlobalPreferences,
    val beforePacks: List<BackupLocalPack>,
    val afterPacks: List<BackupLocalPack>
)

/** Only the journal is persistent; a cancelled download/backup is not remembered. */
internal class PersonalRestoreJournalStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "personal-restore-journal.json")
    private val atomic = AtomicFile(file)
    fun exists(): Boolean = file.exists() || File(file.path + ".bak").exists() ||
        File(file.path + ".new").exists()

    fun write(journal: PersonalRestoreJournal) {
        require(runCatching { UUID.fromString(journal.operationId) }.isSuccess)
        val bytes = personalBackupJson.encodeToString(journal).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_RESTORE_JOURNAL_BYTES) { "Restore journal too large" }
        rejectSymlink(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    fun read(): PersonalRestoreJournal {
        rejectSymlink(file)
        val value = atomic.openRead().use { it.readLimited(MAX_RESTORE_JOURNAL_BYTES) }
        return personalBackupJson.decodeFromString<PersonalRestoreJournal>(value.decodeToString())
            .also { journal ->
                require(runCatching { UUID.fromString(journal.operationId) }.isSuccess)
                journal.beforePacks.forEach { PackId(it.packId) }
                journal.afterPacks.forEach { PackId(it.packId) }
            }
    }

    fun delete() {
        rejectSymlink(file)
        atomic.delete()
        if (exists()) throw IOException("Failed to delete restore journal")
    }
}

/** Read/write only user-owned JSON in local/<pack-id>/; never touch installed .pack data. */
internal class PersonalLocalBackupFiles(context: Context) {
    private val root = File(context.applicationContext.filesDir, "local")
    private val preferences = LocalPackPreferencesStore(context)
    private val ratings = LocalRatingConfigurationStore(context)
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun readAll(): List<BackupLocalPack> {
        rejectSymlink(root)
        if (!root.exists()) return emptyList()
        require(root.isDirectory) { "local/ is not a directory" }
        return root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            rejectSymlink(dir)
            val id = PackId.parse(dir.name)  // Refuse unusual paths instead of following them.
            read(id)
        }.sortedBy { it.packId }
    }

    fun read(id: PackId): BackupLocalPack? {
        val savedPreferences = preferences.readOrNull(id)?.toBackup()
        val ratingFile = ratings.path(id)
        val savedRating = readRatingFile(ratingFile)
        return if (savedPreferences == null && savedRating == null) null else
            BackupLocalPack(id.value, savedPreferences, savedRating)
    }

    fun apply(packs: List<BackupLocalPack>, ids: Set<PackId>) {
        val byId = packs.associateBy { PackId(it.packId) }
        require(byId.size == packs.size) { "Duplicate local pack ID" }
        ids.forEach { id ->
            val entry = byId[id]
            if (read(id) == entry) return@forEach
            if (entry?.preferences == null) preferences.delete(id)
            else preferences.write(id, entry.preferences.toPersonal())
            if (entry?.rating == null) ratings.delete(id)
            else ratings.write(id, entry.rating.toDefinition())
        }
    }

    private fun readRatingFile(file: File): BackupRatingConfiguration? {
        rejectSymlink(file)
        val backup = File(file.path + ".bak")
        val newer = File(file.path + ".new")
        listOf(backup, newer).forEach(::rejectSymlink)
        if (!file.exists() && !backup.exists() && !newer.exists()) return null
        val decoded = json.decodeFromString<BackupRatingConfiguration>(
            AtomicFile(file).openRead().use { it.readLimited(MAX_RATING_FILE_BYTES) }.decodeToString()
        )
        val original = decoded.toDefinition()
        validateRatingConfiguration(original.config, original.profiles)
        return decoded
    }
}

internal fun PersonalPackPreferences.toBackup() = BackupPackPreferences(
    showImages = showImages, stickyScore = stickyScore, mergeEnabled = mergeEnabled,
    useGroupCoversForItems = useGroupCoversForItems,
    elaborateRatingEnabled = elaborateRatingEnabled, catalogSortOrder = catalogSortOrder
)
internal fun BackupPackPreferences.toPersonal() = PersonalPackPreferences(
    showImages = showImages, stickyScore = stickyScore, mergeEnabled = mergeEnabled,
    useGroupCoversForItems = useGroupCoversForItems,
    elaborateRatingEnabled = elaborateRatingEnabled, catalogSortOrder = catalogSortOrder
)

private const val MAX_RESTORE_JOURNAL_BYTES = 64 * 1024 * 1024
private const val MAX_RATING_FILE_BYTES = 256 * 1024
