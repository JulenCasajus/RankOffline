package org.rankoffline.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class CatalogSubtitlePart(
    val fieldId: String,
    val label: String,
    val values: List<String>,
    val compact: Boolean
)

@Composable
internal fun CatalogSubtitlePart.displayText(): String = if (compact) pluralStringResource(
    R.plurals.subtitle_values_of_field, values.size, values.size, label
) else values.joinToString(", ")

internal fun PackManifest.subtitleDefinitions(): List<PackFieldDefinition> =
    subtitle.mapNotNull { id -> fields.firstOrNull { it.id == id } }

internal fun MediaListEntry.subtitleParts(
    fields: List<PackFieldDefinition>, languageTag: String
): List<CatalogSubtitlePart> {
    val attributes = when (this) {
        is MediaListEntry.Item -> value.attributes
        is MediaListEntry.Group -> value.attributes
    }
    return fields.mapNotNull { field ->
        val values = attributes[field.id].orEmpty().mapNotNull { value -> when (value) {
            is CatalogAttributeValue.Text -> value.value.trim().takeIf(String::isNotEmpty)
            is CatalogAttributeValue.Number -> if (field.type == CatalogFieldType.INTEGER &&
                value.value.isFinite() && value.value == value.value.toLong().toDouble())
                value.value.toLong().toString() else value.value.toString()
            is CatalogAttributeValue.Flag -> value.value.toString()
        } }.distinct()
        if (values.isEmpty()) null else CatalogSubtitlePart(
            field.id,
            field.labels[languageTag] ?: field.labels[languageTag.substringBefore('-')]
                ?: field.labels["en"] ?: field.id,
            values,
            (field.multiple || this is MediaListEntry.Group) &&
                (values.size >= 3 || values.joinToString(", ").length > 30)
        )
    }
}

internal fun MediaListEntry.subtitleText(fields: List<PackFieldDefinition>): String =
    subtitleParts(fields, "en").flatMap { it.values }.joinToString(" · ")

/** Separate click targets prevent a compact field from opening group details. */
@Composable
internal fun CatalogSubtitles(
    entry: MediaListEntry, fields: List<PackFieldDefinition>,
    onGroupDetails: (() -> Unit)?, modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE
) {
    val languageTag = LocalConfiguration.current.locales[0]?.toLanguageTag() ?: "en"
    val parts = remember(entry, fields, languageTag) { entry.subtitleParts(fields, languageTag) }
    var expandedField by remember(entry.key) { mutableStateOf<String?>(null) }
    FlowRow(modifier, maxLines = maxLines, horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (entry is MediaListEntry.Group) {
            Text(stringResource(R.string.group_entries_subtitle_count, entry.memberCount),
                style = MaterialTheme.typography.bodySmall,
                color = if (onGroupDetails == null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                modifier = if (onGroupDetails == null) Modifier else
                    Modifier.clickable(role = Role.Button, onClick = onGroupDetails))
        }
        parts.forEachIndexed { index, part ->
            if (index > 0 || entry is MediaListEntry.Group) Text(
                if (index == 0 && entry is MediaListEntry.Group) "|" else "·",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(part.displayText(), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (part.compact) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (part.compact) Modifier.clickable(role = Role.Button) {
                    expandedField = part.fieldId
                } else Modifier)
        }
    }
    parts.firstOrNull { it.fieldId == expandedField }?.let { part ->
        AlertDialog(onDismissRequest = { expandedField = null },
            title = { Text(part.label) },
            text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(part.values, key = { it }) { value ->
                    Text(value, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
            } },
            confirmButton = { TextButton(onClick = { expandedField = null }) {
                Text(stringResource(android.R.string.ok))
            } })
    }
}
