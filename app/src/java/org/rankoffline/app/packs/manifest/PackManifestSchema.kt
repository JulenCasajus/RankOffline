package org.rankoffline.app

import org.json.JSONArray

/** Dynamic field specifications, filter widgets, and subtitle references. */
internal object PackManifestSchema {
    private const val MAX_FIELDS = 128
    private const val MAX_FILTERS = 128

    fun fields(array: JSONArray): List<PackFieldDefinition> {
        require(array.length() <= MAX_FIELDS) { "Too many fields" }
        val fields = array.objects().map { entry ->
            entry.requireOnlyKeys("id", "type", "multiple", "labels", "options")
            val type = when (entry.getString("type")) {
                "text" -> CatalogFieldType.TEXT
                "integer" -> CatalogFieldType.INTEGER
                "decimal" -> CatalogFieldType.DECIMAL
                "boolean" -> CatalogFieldType.BOOLEAN
                "date" -> CatalogFieldType.DATE
                "choice" -> CatalogFieldType.CHOICE
                else -> throw IllegalArgumentException("Unsupported catalog field type")
            }
            val options = entry.optJSONArray("options")?.strings(256)?.also { values ->
                require(values.distinct().size == values.size) { "Duplicate field options" }
                require(values.all { it.isValidManifestText(4096) })
            }.orEmpty()
            require(options.isEmpty() || type == CatalogFieldType.CHOICE) {
                "Options are only supported for choice fields"
            }
            PackFieldDefinition(
                id = entry.getString("id").also(PackManifestJson::requireFieldId),
                type = type,
                multiple = entry.getBoolean("multiple"),
                labels = entry.optJSONObject("labels")?.localizedNames().orEmpty(),
                options = options.toSet()
            )
        }
        require(fields.map { it.id }.distinct().size == fields.size) { "Duplicate field IDs" }
        return fields
    }

    fun filters(
        array: JSONArray,
        fieldsById: Map<String, PackFieldDefinition>,
        capabilities: PackCapabilities
    ): List<PackFilterDefinition> {
        require(array.length() <= MAX_FILTERS) { "Too many filters" }
        val filters = array.objects().map { entry ->
            entry.requireOnlyKeys("id", "field", "type", "order", "labels")
            val field = entry.getString("field").also(PackManifestJson::requireFieldId)
            val fieldSpec = fieldsById[field] ?: throw IllegalArgumentException("Unknown filter field: $field")
            val type = PackFilterType.parse(entry.getString("type"))
            require(
                (type == PackFilterType.RANGE && fieldSpec.type in
                    setOf(CatalogFieldType.INTEGER, CatalogFieldType.DECIMAL, CatalogFieldType.DATE)) ||
                    (type == PackFilterType.MULTI_SELECT && fieldSpec.type in
                        setOf(CatalogFieldType.TEXT, CatalogFieldType.CHOICE, CatalogFieldType.BOOLEAN))
            ) { "Incompatible filter widget for field $field" }
            PackFilterDefinition(
                id = entry.getString("id").also(PackManifestJson::requireFieldId),
                field = field,
                type = type,
                order = entry.getInt("order").also { require(it >= 0) },
                labels = entry.optJSONObject("labels")?.localizedNames().orEmpty()
            )
        }.sortedWith(compareBy(PackFilterDefinition::order, PackFilterDefinition::id))
        require(filters.map { it.id }.distinct().size == filters.size) { "Duplicate filter IDs" }
        require(capabilities.filters || filters.isEmpty()) { "Filters declared but capability is disabled" }
        return filters
    }

    fun subtitle(array: JSONArray, fieldsById: Map<String, PackFieldDefinition>): List<String> =
        array.strings(16).also { subtitle ->
            require(subtitle.distinct().size == subtitle.size && subtitle.all { it in fieldsById }) {
                "Subtitles must reference distinct declared fields"
            }
        }
}
