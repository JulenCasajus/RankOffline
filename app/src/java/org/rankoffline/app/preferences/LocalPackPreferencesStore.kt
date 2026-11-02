package org.rankoffline.app

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * User-editable per-pack preferences at filesDir/local/<pack-id>/preferences.json.
 * No rating definitions or global app settings live here; removing a pack never deletes it.
 * If absent, the caller supplies defaults from the installed manifest and onboarding.
 */
internal class LocalPackPreferencesStore(context: Context) {
    private val root = File(context.applicationContext.filesDir.canonicalFile, "local")
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun readOrCreate(packId: PackId, defaults: PersonalPackPreferences): PersonalPackPreferences {
        val existing = readOrNull(packId)
        if (existing != null) return existing
        write(packId, defaults)
        return defaults
    }

    /** An invalid file throws rather than being silently overwritten. */
    fun readOrNull(packId: PackId): PersonalPackPreferences? {
        val file = target(packId, createDirectory = false)
        val backup = File(file.path + ".bak")
        val newer = File(file.path + ".new")
        if (!file.exists() && !backup.exists() && !newer.exists()) return null
        val bytes = AtomicFile(file).openRead().use { it.readLimited(MAX_BYTES) }
        return json.decodeFromString<PersonalPackPreferences>(bytes.toString(Charsets.UTF_8))
            .also(::validate)
    }

    fun write(packId: PackId, preferences: PersonalPackPreferences) {
        validate(preferences)
        val file = target(packId)
        val payload = json.encodeToString(preferences).toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_BYTES) { "Pack preferences exceed size limit" }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(payload)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    /** Called only for explicit removal of personal settings, not pack uninstall. */
    fun delete(packId: PackId) {
        val file = target(packId, createDirectory = false)
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach {
            rejectSymlink(it)
            if (it.exists() && !it.delete()) throw IOException("Unable to delete ${it.name}")
        }
    }

    fun path(packId: PackId): File = target(packId, createDirectory = false)

    private fun target(packId: PackId, createDirectory: Boolean = true): File {
        val folder = File(root, packId.value.toString())
        if (createDirectory) {
            ensureDirectory(root)
            ensureDirectory(folder)
        } else {
            rejectSymlink(root)
            rejectSymlink(folder)
        }
        val file = File(folder, "preferences.json")
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach(::rejectSymlink)
        return file
    }

    private fun ensureDirectory(folder: File) {
        rejectSymlink(folder)
        if (!folder.isDirectory && !folder.mkdir()) throw IOException("Cannot create ${folder.name}")
        rejectSymlink(folder)
    }


    private fun validate(preferences: PersonalPackPreferences) {
        // Enums are validated by kotlinx.serialization when reading JSON.
        // Nullable fields intentionally allow manual reversion to package defaults.
        preferences.catalogSortOrder?.let { require(it in CatalogSortOrder.entries) }
    }


    private companion object { const val MAX_BYTES = 64 * 1024 }
}
