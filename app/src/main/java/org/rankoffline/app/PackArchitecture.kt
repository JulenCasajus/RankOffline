package org.rankoffline.app

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Stable identity of a data pack. It is deliberately independent from UI media enums. */
@JvmInline
value class PackId(val value: String) {
    init {
        require(value.matches(VALID)) { "Invalid pack id: $value" }
    }

    companion object {
        private val VALID = Regex("[a-z][a-z0-9-]{1,63}")
    }
}

object OfficialPackIds {
    val Anime = PackId("animes")
    val Manga = PackId("mangas")
    val Movies = PackId("movies")
    val Series = PackId("series")
    val Books = PackId("books")

    val all: List<PackId> = listOf(Anime, Books, Manga, Movies, Series)
}

fun MediaType.toPackId(): PackId = when (this) {
    MediaType.ANIME -> OfficialPackIds.Anime
    MediaType.MANGA -> OfficialPackIds.Manga
    MediaType.MOVIE -> OfficialPackIds.Movies
    MediaType.SERIES -> OfficialPackIds.Series
    MediaType.BOOK -> OfficialPackIds.Books
}

fun PackId.toOfficialMediaTypeOrNull(): MediaType? = when (this) {
    OfficialPackIds.Anime -> MediaType.ANIME
    OfficialPackIds.Manga -> MediaType.MANGA
    OfficialPackIds.Movies -> MediaType.MOVIE
    OfficialPackIds.Series -> MediaType.SERIES
    OfficialPackIds.Books -> MediaType.BOOK
    else -> null
}

enum class PackFilterType(val wireValue: String) {
    MULTI_SELECT("multi_select"),
    RANGE("range");

    companion object {
        fun parse(value: String): PackFilterType = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unsupported pack filter type: $value")
    }
}

enum class PackMergeStrategy {
    NONE,
    GROUP_ID,
    COLLECTION_ID,
    RELATION_GRAPH;

    companion object {
        fun parse(value: String): PackMergeStrategy = entries.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Unsupported pack merge strategy: $value")
    }
}

data class PackProvider(val id: String, val name: String)

data class PackCapabilities(
    val search: Boolean,
    val filters: Boolean,
    val covers: Boolean,
    val popularity: Boolean,
    val merge: Boolean,
    val ratings: Boolean
)

data class PackFilterDefinition(
    val id: String,
    val field: String,
    val type: PackFilterType,
    val order: Int
)

data class PackMergeDefinition(
    val enabled: Boolean,
    val strategy: PackMergeStrategy,
    val groupType: String?
)

data class PackDefaults(
    val showImages: Boolean,
    val stickyScoreHeader: Boolean,
    val mergeEnabled: Boolean,
    val ratingMode: String,
    /** Default only for a NEW pack installation; the user's saved choice takes precedence. */
    val oneCoverPerGroup: Boolean = false
)

data class PackManifestFile(
    val path: String,
    val bytes: Long,
    val sha256: String
)

data class PackManifest(
    val schemaVersion: Int,
    val packId: PackId,
    val displayName: String,
    val version: String,
    val minRankOfflineVersion: String,
    val provider: PackProvider,
    val catalog: List<String>,
    val groups: List<String>,
    val covers: List<String>,
    val capabilities: PackCapabilities,
    val filters: List<PackFilterDefinition>,
    val subtitle: List<String>,
    val merge: PackMergeDefinition,
    val defaults: PackDefaults,
    val files: List<PackManifestFile>
)

data class PackCoverRecord(val canonicalId: String, val relativePath: String)

fun PackManifest.readCoverIndex(installedDirectory: File): List<PackCoverRecord> {
    val index = File(installedDirectory, "metadata/cover_index.json")
    if (!index.isFile) return emptyList()
    val declared = files.mapTo(hashSetOf()) { it.path }
    return JSONObject(index.readText()).getJSONArray("covers").objects().map { value ->
        val path = value.getString("path").also(PackManifestParser::validateDataPath)
        require(path.startsWith("covers/") && path in declared)
        val resolved = File(installedDirectory, path)
        require(resolved.isFile && resolved.canonicalPath.startsWith(installedDirectory.canonicalPath + File.separator))
        PackCoverRecord(
            canonicalId = value.getString("canonicalId").also { require(it.isNotBlank()) },
            relativePath = path
        )
    }
}

data class PackDefinition(
    val manifest: PackManifest,
    val installed: Boolean,
    val installedBytes: Long,
    val downloadBytes: Long?
) {
    val packId: PackId get() = manifest.packId
}

/** Single runtime source of truth for installed data-only packs. */
class PackRegistry(private val root: File) {
    private val _definitions = MutableStateFlow<Map<PackId, PackDefinition>>(emptyMap())
    val definitions: StateFlow<Map<PackId, PackDefinition>> = _definitions.asStateFlow()

    fun refresh(downloadBytes: Map<PackId, Long?> = emptyMap()): Map<PackId, PackDefinition> {
        val discovered = root.listFiles().orEmpty().asSequence()
            .filter(File::isDirectory)
            .mapNotNull { directory ->
                val encoded = File(directory, CategoryPackInstaller.PACK_MANIFEST)
                    .takeIf(File::isFile)?.readText() ?: return@mapNotNull null
                val manifest = runCatching { PackManifestParser.parse(encoded) }.getOrNull()
                    ?: return@mapNotNull null
                if (directory.name != manifest.packId.value ||
                    !File(directory, CategoryPackInstaller.COMPLETE_MARKER).isFile
                ) return@mapNotNull null
                manifest.packId to PackDefinition(
                    manifest = manifest,
                    installed = true,
                    installedBytes = manifest.files.sumOf(PackManifestFile::bytes),
                    downloadBytes = downloadBytes[manifest.packId]
                )
            }.toMap()
        _definitions.value = discovered
        return discovered
    }

    fun definition(packId: PackId): PackDefinition? = _definitions.value[packId]
}

/** Strict DATA-ONLY manifest parser shared by official and future external packs. */
object PackManifestParser {
    const val SCHEMA_VERSION = 1
    private val SHA_256 = Regex("[0-9a-f]{64}")
    private val FIELD_ID = Regex("[a-z][a-z0-9_]{0,63}")
    private val FORBIDDEN_SUFFIXES = setOf(".dex", ".jar", ".class", ".so", ".apk")

    fun parse(encoded: String): PackManifest = parse(JSONObject(encoded))

    fun parse(root: JSONObject): PackManifest {
        val schemaVersion = root.getInt("schemaVersion")
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported pack schema: $schemaVersion" }
        val packId = PackId(root.getString("packId"))
        val providerJson = root.getJSONObject("provider")
        val capabilityJson = root.getJSONObject("capabilities")
        val filters = root.getJSONArray("filters").objects().map { value ->
            PackFilterDefinition(
                id = value.getString("id").also { require(it.matches(FIELD_ID)) },
                field = value.getString("field").also { require(it.matches(FIELD_ID)) },
                type = PackFilterType.parse(value.getString("type")),
                order = value.getInt("order").also { require(it >= 0) }
            )
        }.sortedBy(PackFilterDefinition::order)
        require(filters.map(PackFilterDefinition::id).distinct().size == filters.size)
        val subtitle = root.getJSONArray("subtitle").strings()
        require(subtitle.all { field -> filters.any { it.field == field } }) {
            "Subtitle fields must be declared filters"
        }
        val mergeJson = root.getJSONObject("merge")
        val merge = PackMergeDefinition(
            enabled = mergeJson.getBoolean("enabled"),
            strategy = PackMergeStrategy.parse(mergeJson.getString("strategy")),
            groupType = mergeJson.optString("groupType").takeIf(String::isNotBlank)
        )
        require(!merge.enabled || merge.strategy != PackMergeStrategy.NONE)
        val defaultsJson = root.getJSONObject("defaults")
        val integrity = root.getJSONObject("integrity")
        val files = integrity.getJSONArray("files").objects().map { value ->
            PackManifestFile(
                path = value.getString("path").also(::validateDataPath),
                bytes = value.getLong("bytes").also { require(it >= 0L) },
                sha256 = value.getString("sha256").lowercase().also { require(it.matches(SHA_256)) }
            )
        }
        require(files.isNotEmpty())
        require(files.map(PackManifestFile::path).distinct().size == files.size)
        require(files.sumOf(PackManifestFile::bytes) == integrity.getLong("installedBytes"))
        val declaredPaths = files.mapTo(hashSetOf()) { it.path }
        val sections = listOf("catalog", "groups", "covers").associateWith { section ->
            root.getJSONArray(section).strings().also { paths -> paths.forEach { path ->
                validateDataPath(path)
                require(path in declaredPaths && path.startsWith("$section/")) {
                    "$section references an undeclared payload"
                }
            } }
        }
        require(sections.getValue("catalog").isNotEmpty()) { "A pack requires catalog data" }
        return PackManifest(
            schemaVersion = schemaVersion,
            packId = packId,
            displayName = root.getString("displayName").also { require(it.isNotBlank()) },
            version = root.getString("version").also { require(it.isNotBlank()) },
            minRankOfflineVersion = root.getString("minRankOfflineVersion").also { require(it.isNotBlank()) },
            provider = PackProvider(
                id = providerJson.getString("id").also { require(it.matches(FIELD_ID)) },
                name = providerJson.getString("name").also { require(it.isNotBlank()) }
            ),
            catalog = sections.getValue("catalog"),
            groups = sections.getValue("groups"),
            covers = sections.getValue("covers"),
            capabilities = PackCapabilities(
                search = capabilityJson.getBoolean("search"),
                filters = capabilityJson.getBoolean("filters"),
                covers = capabilityJson.getBoolean("covers"),
                popularity = capabilityJson.getBoolean("popularity"),
                merge = capabilityJson.getBoolean("merge"),
                ratings = capabilityJson.getBoolean("ratings")
            ),
            filters = filters,
            subtitle = subtitle,
            merge = merge,
            defaults = PackDefaults(
                showImages = defaultsJson.getBoolean("showImages"),
                stickyScoreHeader = defaultsJson.getBoolean("stickyScoreHeader"),
                mergeEnabled = defaultsJson.getBoolean("mergeEnabled"),
                ratingMode = defaultsJson.getString("ratingMode").also {
                    require(it == "simple" || it == "elaborated")
                },
                // Older official Series manifests have no such field; keep their intended default.
                oneCoverPerGroup = defaultsJson.optBoolean(
                    "oneCoverPerGroup", packId == OfficialPackIds.Series
                )
            ),
            files = files
        )
    }

    fun validateDataPath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path)
        require(path.split('/').none { it.isBlank() || it == "." || it == ".." })
        require(FORBIDDEN_SUFFIXES.none { path.lowercase().endsWith(it) }) {
            "Executable payloads are forbidden"
        }
        require(path.startsWith("catalog/") || path.startsWith("groups/") ||
            path.startsWith("covers/") || path.startsWith("metadata/")) {
            "Pack payload path is outside supported data directories"
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = List(length()) { index -> getJSONObject(index) }
private fun JSONArray.strings(): List<String> = List(length()) { index -> getString(index) }

fun PackManifest.creditRole(): String? = filters.firstNotNullOfOrNull { filter ->
    when (filter.field) {
        "studio" -> "STUDIO"
        "author" -> "AUTHOR"
        "production_company" -> "PRODUCTION_COMPANY"
        "network" -> "NETWORK"
        else -> null
    }
}

fun PackManifest.groupTypeOrNull(): String? = merge.groupType.takeIf {
    capabilities.merge && merge.enabled && merge.strategy != PackMergeStrategy.NONE
}

/** Bootstrap metadata used before a pack is installed; installed manifests use the same fields. */
fun MediaType.officialSubtitleFields(): List<String> = when (this) {
    MediaType.ANIME -> listOf("genre", "format", "year", "studio")
    MediaType.MANGA -> listOf("genre", "year", "author")
    MediaType.MOVIE -> listOf("genre", "year", "production_company")
    MediaType.SERIES -> listOf("genre", "year", "network")
    MediaType.BOOK -> listOf("subject", "year", "author")
}

