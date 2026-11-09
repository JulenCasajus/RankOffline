package org.rankoffline.app

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
