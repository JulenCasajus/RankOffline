package org.rankoffline.app

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Editable, local, per-pack copy of rating categories and profiles.
 *
 * filesDir/local/<pack-id>/rating/rating.json
 *
 * This file contains NO bonus/score rules, catalog data or user ratings. It survives uninstall.
 * A missing file is recreated from the manifest the next time the installed pack is synchronized.
 * File writes are atomic. No writes to the installed pack or to DataStore.
 */
internal class LocalRatingConfigurationStore(context: Context) {
    private val base = File(context.filesDir.canonicalFile, "local")
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    @Serializable
    private data class LocalRatingFile(
        val categories: List<RatingCategory>,
        val profiles: List<RatingProfile>
    ) {
        fun asDefinition() = PackRatingDefinition(
            RatingSystemConfig(categories), RatingProfilesConfig(profiles)
        )
    }

    fun readOrCreate(
        packId: PackId,
        original: PackRatingDefinition
    ): PackRatingDefinition {
        val target = target(packId)
        val atomic = AtomicFile(target)
        if (target.exists() || File(target.path + ".bak").exists() || File(target.path + ".new").exists()) {
            val bytes = atomic.openRead().use { it.readLimited(MAX_BYTES) }
            return json.decodeFromString<LocalRatingFile>(bytes.toString(Charsets.UTF_8))
                .asDefinition().also { validateRatingConfiguration(it.config, it.profiles) }
        }
        write(packId, original)
        return original
    }

    fun write(packId: PackId, value: PackRatingDefinition) {
        validateRatingConfiguration(value.config, value.profiles)
        val target = target(packId)
        val bytes = json.encodeToString(LocalRatingFile(
            categories = value.config.categories,
            profiles = value.profiles.profiles
        )).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Rating configuration is too large" }
        require(target.parentFile?.isDirectory == true)
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    /** Explicitly destructive: removes only the editable rating copy for this PackId. */
    fun delete(packId: PackId) {
        val file = target(packId, createDirectory = false)
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach { child ->
            if (child.exists() && !child.delete()) throw IOException("Cannot remove ${child.name}")
        }
    }

    /** Useful for the settings screen and documentation, not a public filesystem authority. */
    fun path(packId: PackId): File = target(packId, createDirectory = false)

    private fun target(packId: PackId, createDirectory: Boolean = true): File {
        val directory = File(File(base, packId.value.toString()), "rating")
        if (createDirectory) {
            ensureDirectory(base)
            ensureDirectory(directory.parentFile!!)
            ensureDirectory(directory)
        } else {
            listOf(base, directory.parentFile!!, directory).forEach(::rejectSymlink)
        }
        val file = File(directory, "rating.json")
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach(::rejectSymlink)
        return file
    }

    private fun ensureDirectory(dir: File) {
        rejectSymlink(dir)
        if (!dir.isDirectory && !dir.mkdir()) {
            throw IOException("Cannot create ${dir.name}")
        }
        rejectSymlink(dir)
    }


    private companion object { const val MAX_BYTES = 256 * 1024 }
}
