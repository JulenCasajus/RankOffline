package org.rankoffline.app

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONObject

/** Independently invalidated cached statistics. True means recompute on next request. */
enum class PackSummaryField {
    INSTALLED_BYTES, COVER_BYTES, ITEM_COUNT, GROUP_COUNT,
    AVAILABLE_COVER_COUNT, MISSING_COVER_COUNT
}

/** Advisory cache only; never authorizes an install, determines READY, or stores personal ratings. */
data class LocalPackSummary(
    val installedBytes: Long = 0L,
    val coverBytes: Long = 0L,
    val itemCount: Long = 0L,
    val groupCount: Long = 0L,
    val availableCoverCount: Long = 0L,
    val missingCoverCount: Long = 0L,
    val dirty: Set<PackSummaryField> = PackSummaryField.entries.toSet(),
    /** Only valid when the file stamp matches AND Room's import fingerprint matches. */
    val catalogReady: Boolean = false,
    val manifestSize: Long = -1L,
    val manifestModified: Long = -1L,
    val completeModified: Long = -1L,
    val catalogModified: Long = -1L,
    val relationsModified: Long = -1L,
    val coversModified: Long = -1L,
    val itemsCoverModified: Long = -1L,
    val groupsCoverModified: Long = -1L,
    val updatedAt: Long = 0L,
    /** Last completed batch, NOT a permanent failure state nor a download queue. */
    val lastBatchTarget: Int = 0,
    val lastBatchDownloaded: Int = 0,
    val lastBatchUnavailable: Int = 0,
    val lastBatchErrors: Int = 0
) {
    fun isDirty(field: PackSummaryField): Boolean = field in dirty
    val installedStatsValid: Boolean get() = !isDirty(PackSummaryField.INSTALLED_BYTES) &&
        !isDirty(PackSummaryField.COVER_BYTES)
    val catalogStatsValid: Boolean get() = !isDirty(PackSummaryField.ITEM_COUNT) &&
        !isDirty(PackSummaryField.GROUP_COUNT)
    val coverStatsValid: Boolean get() = !isDirty(PackSummaryField.AVAILABLE_COVER_COUNT) &&
        !isDirty(PackSummaryField.MISSING_COVER_COUNT)

    /** Fast, deliberately conservative marker check; no directory traversal. */
    fun matches(directory: File): Boolean {
        val manifest = File(directory, PackInstaller.PACK_MANIFEST)
        val marker = File(directory, PackInstaller.COMPLETE_MARKER)
        return manifest.isFile && marker.isFile &&
            manifest.length() == manifestSize && manifest.lastModified() == manifestModified &&
            marker.lastModified() == completeModified &&
            File(directory, "catalog").lastModified() == catalogModified &&
            File(directory, "groups").lastModified() == relationsModified
    }

    fun matchesStorage(directory: File): Boolean = matches(directory) &&
        File(directory, "covers").lastModified() == coversModified &&
        File(directory, "covers/items").lastModified() == itemsCoverModified &&
        File(directory, "covers/groups").lastModified() == groupsCoverModified
}

/** A single small JSON per pack: filesDir/local/<pack-id>/summary.json. */
class LocalPackSummaryStore(private val localRoot: File) {
    private val lock = locks.computeIfAbsent(localRoot.absolutePath) { Any() }

    fun read(packId: PackId): LocalPackSummary? = synchronized(lock) {
        val file = summaryFile(packId)
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() !in 1..MAX_BYTES)
            return@synchronized null
        runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.getInt("schema") != 2 || json.getLong("packId") != packId.value) return@runCatching null
            val dirtyObject = json.getJSONObject("dirty")
            val dirty = PackSummaryField.entries.filterTo(mutableSetOf()) { field ->
                dirtyObject.getBoolean(field.name)
            }
            LocalPackSummary(
                installedBytes = json.getLong("installedBytes").requireNonNegative(),
                coverBytes = json.getLong("coverBytes").requireNonNegative(),
                itemCount = json.getLong("itemCount").requireNonNegative(),
                groupCount = json.getLong("groupCount").requireNonNegative(),
                availableCoverCount = json.getLong("availableCoverCount").requireNonNegative(),
                missingCoverCount = json.getLong("missingCoverCount").requireNonNegative(),
                dirty = dirty,
                catalogReady = json.optBoolean("catalogReady", false),
                manifestSize = json.getLong("manifestSize"),
                manifestModified = json.getLong("manifestModified"),
                completeModified = json.getLong("completeModified"),
                catalogModified = json.optLong("catalogModified", -1L),
                relationsModified = json.optLong("relationsModified", -1L),
                coversModified = json.optLong("coversModified", -1L),
                itemsCoverModified = json.optLong("itemsCoverModified", -1L),
                groupsCoverModified = json.optLong("groupsCoverModified", -1L),
                updatedAt = json.getLong("updatedAt"),
                lastBatchTarget = json.optInt("lastBatchTarget", 0).also { require(it >= 0) },
                lastBatchDownloaded = json.optInt("lastBatchDownloaded", 0).also { require(it >= 0) },
                lastBatchUnavailable = json.optInt("lastBatchUnavailable", 0).also { require(it >= 0) },
                lastBatchErrors = json.optInt("lastBatchErrors", 0).also { require(it >= 0) }
            ).takeIf { it.coverBytes <= it.installedBytes }
        }.getOrNull()
    }

    fun update(packId: PackId, change: (LocalPackSummary) -> LocalPackSummary) = synchronized(lock) {
        write(packId, change(read(packId) ?: LocalPackSummary()))
    }

    /** Persist invalidation BEFORE the associated filesystem/Room mutation. */
    fun markDirty(packId: PackId, vararg fields: PackSummaryField) = synchronized(lock) {
        if (fields.isEmpty()) return@synchronized
        val loaded = read(packId)
        val previous = loaded ?: LocalPackSummary()
        val updated = previous.dirty + fields
        // Thousands of operations in the same batch require only the first write.
        // A corrupt/unknown-schema cache must be replaced, not treated as already dirty.
        if (loaded == null || updated != previous.dirty) {
            write(packId, previous.copy(dirty = updated))
        }
    }

    fun markAllDirty(packId: PackId) =
        markDirty(packId, *PackSummaryField.entries.toTypedArray())

    private fun summaryFile(packId: PackId): File = File(File(localRoot, packId.value.toString()), "summary.json")

    private fun write(packId: PackId, value: LocalPackSummary) {
        val file = summaryFile(packId)
        val directory = file.parentFile ?: error("Missing local pack directory")
        check((localRoot.isDirectory || localRoot.mkdirs()) &&
            !Files.isSymbolicLink(localRoot.toPath()))
        check((directory.isDirectory || directory.mkdirs()) &&
            !Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(file.toPath()))
        val dirtyJson = JSONObject()
        PackSummaryField.entries.forEach { dirtyJson.put(it.name, value.isDirty(it)) }
        val json = JSONObject().put("schema", 2).put("packId", packId.value)
            .put("installedBytes", value.installedBytes).put("coverBytes", value.coverBytes)
            .put("itemCount", value.itemCount).put("groupCount", value.groupCount)
            .put("availableCoverCount", value.availableCoverCount)
            .put("missingCoverCount", value.missingCoverCount)
            .put("dirty", dirtyJson)
            .put("catalogReady", value.catalogReady)
            .put("manifestSize", value.manifestSize).put("manifestModified", value.manifestModified)
            .put("completeModified", value.completeModified)
            .put("catalogModified", value.catalogModified)
            .put("relationsModified", value.relationsModified)
            .put("coversModified", value.coversModified)
            .put("itemsCoverModified", value.itemsCoverModified)
            .put("groupsCoverModified", value.groupsCoverModified)
            .put("updatedAt", value.updatedAt)
            .put("lastBatchTarget", value.lastBatchTarget)
            .put("lastBatchDownloaded", value.lastBatchDownloaded)
            .put("lastBatchUnavailable", value.lastBatchUnavailable)
            .put("lastBatchErrors", value.lastBatchErrors)
        val temporary = File(directory, ".summary-${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(json.toString(), Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }

    private fun Long.requireNonNegative(): Long = also { require(it >= 0) }
    private companion object {
        const val MAX_BYTES = 16_384L
        val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}
