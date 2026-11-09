package org.rankoffline.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Entry row is keyed by (pack, kind, ID); artwork can use a different, shared cover key. */
@Composable
internal fun MediaEntryRow(
    entry: MediaListEntry, settings: AppSettingsState,
    coverRepository: CoverRepository, bitmapCache: CoverBitmapCache,
    subtitleFields: List<PackFieldDefinition> = emptyList(),
    showImages: Boolean, onClick: () -> Unit,
    onGroupDetails: () -> Unit,
    onCoverDownload: (CatalogEntryKey) -> Unit,
    onCoverOpen: () -> Unit,
    rank: Int? = null, rankingSortOrder: RankingSortOrder? = null
) {
    val languageTag = LocalConfiguration.current.locales[0]?.toLanguageTag() ?: "en"
    val subtitles = remember(entry, subtitleFields, languageTag) {
        entry.subtitleParts(subtitleFields, languageTag)
    }
    var expandedField by remember(entry.key) { mutableStateOf<String?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = if (entry is MediaListEntry.Group) onGroupDetails else onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (rank != null) Text("$rank.", modifier = Modifier.width(38.dp), fontWeight = FontWeight.SemiBold)
            if (showImages) {
                MediaCover(entry, settings, coverRepository, bitmapCache,
                    onDownloadRequested = onCoverDownload,
                    onCoverClick = { onCoverOpen() },
                    showImages = true, modifier = Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                if (subtitles.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        subtitles.forEachIndexed { index, part ->
                            if (index > 0) Text("·", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val label = if (part.compact) pluralStringResource(
                                R.plurals.subtitle_values_of_field, part.values.size,
                                part.values.size, part.label
                            ) else part.values.joinToString(", ")
                            Text(label, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (part.compact) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = if (part.compact) Modifier.clickable {
                                    expandedField = part.fieldId
                                } else Modifier)
                        }
                    }
                }
                when (entry) {
                    is MediaListEntry.Group -> Text(
                        stringResource(R.string.group_entries_count, entry.memberCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onGroupDetails))
                    is MediaListEntry.Item -> Unit
                }
            }
            val displayed = when (rankingSortOrder) {
                RankingSortOrder.QUALITY_SCORE -> entry.qualityScore
                RankingSortOrder.PERSONAL_TASTE -> entry.additiveScore
                RankingSortOrder.CATEGORY_SCORE -> entry.categoryScore
                else -> entry.finalScore
            }
            if (displayed != null || rankingSortOrder != null) ScoreValueBadge(displayed)
        }
    }
    subtitles.firstOrNull { it.fieldId == expandedField }?.let { part ->
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

internal data class CatalogSubtitlePart(
    val fieldId: String,
    val label: String,
    val values: List<String>,
    val compact: Boolean
)

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
            field.multiple && (values.size >= 4 || values.joinToString(", ").length > 45)
        )
    }
}

internal fun MediaListEntry.subtitleText(fields: List<PackFieldDefinition>): String =
    subtitleParts(fields, "en").flatMap { it.values }.joinToString(" · ")
