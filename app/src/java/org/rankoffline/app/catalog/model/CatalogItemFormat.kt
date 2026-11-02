package org.rankoffline.app

import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Version of the catalog ITEM RECORD format, not PackManifest.schemaVersion.
 * The surrounding .pack manifest owns pack identity, version, file hashes and attribution.
 */
const val MEDIA_CATALOG_FORMAT_VERSION = 2

/** Supported data types, not a list of content categories. */
enum class CatalogFieldType { TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, CHOICE }

/**
 * Structural part of a field definition supplied by PackManifest.fields.
 * Labels, filters and presentation remain in the manifest; these rules validate stored values.
 * A CHOICE field with an empty allowedValues set accepts pack-defined values without an enum.
 */
data class CatalogFieldSpec(
    val id: String,
    val type: CatalogFieldType,
    val multiple: Boolean = false,
    val allowedValues: Set<String> = emptySet()
)

/** An optional alternative title, usable by any catalog. */
@Serializable
data class MediaCatalogAlias(
    val title: String,
    val language: String? = null,
    val kind: String? = null
)

/**
 * A single JSONL item within catalog/ of a .pack.
 * packId is deliberately absent: it comes from the VERIFIED containing PackManifest.
 * Attributes are scalar JSON values or arrays according to PackManifest.fields.
 * Arbitrary field IDs (genre, platform, author, etc.) are never hardcoded by the APK.
 */
@Serializable
data class MediaCatalogRecord(
    val id: String,
    val title: String,
    val provider: String? = null,
    val providerId: String? = null,
    val popularity: Double? = null,
    val popularityRank: Int? = null,
    val remoteCoverRef: String? = null,
    val attributes: Map<String, JsonElement> = emptyMap(),
    val aliases: List<MediaCatalogAlias> = emptyList()
)

/** All rows produced from one VALIDATED record for transactional insertion. */
internal data class MediaCatalogItemRows(
    val item: MediaItemEntity,
    val attributes: List<MediaItemAttributeEntity>,
    val aliases: List<MediaAliasEntity>
)

/** Unknown record properties must NOT be silently discarded during import. */
private val catalogJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = false
    isLenient = false
    allowSpecialFloatingPointValues = false
}

private val FIELD_ID = Regex("[a-z][a-z0-9_]{0,63}")
private val ITEM_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,191}")
private val ISO_DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private val JSON_INTEGER = Regex("-?(?:0|[1-9][0-9]*)")
private const val MAX_FIELDS_PER_ITEM = 128
private const val MAX_VALUES_PER_FIELD = 64
private const val MAX_ALIASES_PER_ITEM = 64
private const val MAX_TITLE_LENGTH = 500
private const val MAX_ATTRIBUTE_TEXT_LENGTH = 4096
private const val MAX_REFERENCE_LENGTH = 1024
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L // Room stores numeric attributes as Double.

/**
 * Decode and validate against fields defined by the containing pack.
 * The caller must FIRST authenticate/validate the .pack and enforce stream size and item count.
 */
fun parseMediaCatalogRecord(
    value: String,
    fields: Map<String, CatalogFieldSpec>
): MediaCatalogRecord = catalogJson.decodeFromString<MediaCatalogRecord>(value).also {
    validateMediaCatalogRecord(it, fields)
}

/** Useful for pack-building tools and round-trip tests; does not replace validation. */
internal fun encodeMediaCatalogRecord(record: MediaCatalogRecord): String =
    catalogJson.encodeToString(record)

/**
 * Validate one item without making any assumptions about what its fields mean.
 * PackManifestParser is responsible for validating the field definitions themselves.
 */
internal fun validateMediaCatalogRecord(
    record: MediaCatalogRecord,
    fields: Map<String, CatalogFieldSpec>
) {
    require(record.id.matches(ITEM_ID)) { "Invalid catalog item ID: ${record.id}" }
    require(record.title.isValidText(MAX_TITLE_LENGTH)) { "Invalid catalog item title" }
    require(record.provider == null || (record.provider.matches(FIELD_ID))) {
        "Invalid catalog provider ID"
    }
    require(record.providerId == null || record.provider != null) {
        "providerId requires provider"
    }
    require(record.providerId == null || record.providerId.isValidText(MAX_REFERENCE_LENGTH)) {
        "Invalid provider ID"
    }
    require(record.popularity == null || (record.popularity.isFinite() && record.popularity >= 0.0)) {
        "Catalog popularity must be finite and nonnegative"
    }
    require(record.popularityRank == null || record.popularityRank >= 0) {
        "Catalog popularity rank must be nonnegative"
    }
    require(record.remoteCoverRef == null || record.remoteCoverRef.isValidText(MAX_REFERENCE_LENGTH)) {
        "Invalid remote cover reference"
    }
    require(record.aliases.size <= MAX_ALIASES_PER_ITEM) { "Too many catalog aliases" }
    require(record.aliases.map(MediaCatalogAlias::title).distinct().size == record.aliases.size) {
        "Duplicate alias titles for item ${record.id}"
    }
    record.aliases.forEach { alias ->
        require(alias.title.isValidText(MAX_TITLE_LENGTH)) { "Invalid catalog alias" }
        require(alias.language == null || alias.language.isValidText(35)) { "Invalid alias language" }
        require(alias.kind == null || alias.kind.isValidText(64)) { "Invalid alias kind" }
    }
    require(record.attributes.size <= MAX_FIELDS_PER_ITEM) { "Too many catalog attributes" }
    record.attributes.forEach { (fieldId, raw) ->
        val spec = fields[fieldId] ?: throw IllegalArgumentException("Unknown catalog field: $fieldId")
        require(spec.id == fieldId && fieldId.matches(FIELD_ID)) { "Invalid field definition: $fieldId" }
        val values = if (raw is JsonArray) {
            require(spec.multiple) { "Field $fieldId does not accept multiple values" }
            require(raw.isNotEmpty()) { "Empty attribute array: $fieldId" }
            raw.toList()
        } else {
            listOf(raw)
        }
        require(values.size <= MAX_VALUES_PER_FIELD) { "Too many values for $fieldId" }
        require(values.distinct().size == values.size) { "Duplicate values for $fieldId" }
        values.forEach { element -> element.toAttributeValue(spec) }
    }
}

/**
 * Transform a validated catalog record into the new Room tables.
 * All rows carry packId. Call inside the importer's transaction.
 */
internal fun MediaCatalogRecord.toEntityRows(
    packId: PackId,
    fields: Map<String, CatalogFieldSpec>
): MediaCatalogItemRows {
    validateMediaCatalogRecord(this, fields)
    val attributeRows = attributes.flatMap { (fieldId, raw) ->
        val spec = requireNotNull(fields[fieldId])
        val values = if (raw is JsonArray) raw.toList() else listOf(raw)
        values.mapIndexed { position, value ->
            val typed = value.toAttributeValue(spec)
            MediaItemAttributeEntity(
                packId = packId.value,
                mediaId = id,
                fieldId = fieldId,
                position = position,
                valueText = typed.text,
                valueNumber = typed.number,
                valueBoolean = typed.boolean
            )
        }
    }
    return MediaCatalogItemRows(
        item = MediaItemEntity(
            packId = packId.value,
            id = id,
            title = title,
            provider = provider,
            providerId = providerId,
            popularity = popularity,
            popularityRank = popularityRank,
            remoteCoverRef = remoteCoverRef
        ),
        attributes = attributeRows,
        aliases = aliases.map { alias ->
            MediaAliasEntity(
                packId = packId.value,
                mediaId = id,
                title = alias.title,
                language = alias.language,
                kind = alias.kind
            )
        }
    )
}

private data class TypedAttributeValue(
    val text: String? = null,
    val number: Double? = null,
    val boolean: Boolean? = null
)

private fun JsonElement.toAttributeValue(spec: CatalogFieldSpec): TypedAttributeValue {
    val value = this as? JsonPrimitive
        ?: throw IllegalArgumentException("Field ${spec.id} must contain scalar values")
    return when (spec.type) {
        CatalogFieldType.TEXT, CatalogFieldType.CHOICE, CatalogFieldType.DATE -> {
            require(value.isString) { "Field ${spec.id} must be a string" }
            val text = value.content
            require(text.isValidText(MAX_ATTRIBUTE_TEXT_LENGTH)) { "Invalid text for ${spec.id}" }
            if (spec.type == CatalogFieldType.DATE) {
                require(text.matches(ISO_DATE) && runCatching { LocalDate.parse(text) }.isSuccess) {
                    "Field ${spec.id} must be a valid ISO-8601 calendar date"
                }
            }
            if (spec.type == CatalogFieldType.CHOICE && spec.allowedValues.isNotEmpty()) {
                require(text in spec.allowedValues) { "Unknown choice for ${spec.id}: $text" }
            }
            TypedAttributeValue(text = text)
        }
        CatalogFieldType.INTEGER -> {
            require(!value.isString && value.content.matches(JSON_INTEGER)) {
                "Field ${spec.id} must be a JSON integer"
            }
            val number = value.content.toLongOrNull()
            require(number != null && number in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) {
                "Integer out of safe Room numeric range: ${spec.id}"
            }
            TypedAttributeValue(number = number.toDouble())
        }
        CatalogFieldType.DECIMAL -> {
            require(!value.isString) { "Field ${spec.id} must be a JSON number" }
            val number = value.doubleOrNull
            require(number != null && number.isFinite()) { "Invalid number for ${spec.id}" }
            TypedAttributeValue(number = number)
        }
        CatalogFieldType.BOOLEAN -> {
            require(!value.isString) { "Field ${spec.id} must be a JSON boolean" }
            val flag = value.booleanOrNull
            require(flag != null) { "Invalid boolean for ${spec.id}" }
            TypedAttributeValue(boolean = flag)
        }
    }
}

private fun String.isValidText(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && none(Char::isISOControl)
