package org.rankoffline.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.util.Locale

/** The manifest is the schema. We never invent Anime/Manga/Books-specific filter fields. */
@Composable
internal fun CatalogFiltersDialog(
    manifest: PackManifest?, current: CatalogFilters,
    options: Map<String, List<CatalogAttributeValue>>,
    onDismiss: () -> Unit, onApply: (CatalogFilters) -> Unit,
    onLoadOptions: () -> Unit
) {
    var draft by remember(manifest?.packId, current) { mutableStateOf(current) }
    LaunchedEffect(manifest?.packId) { if (manifest != null) onLoadOptions() }
    val languageTag = LocalConfiguration.current.locales[0]?.toLanguageTag() ?: Locale.getDefault().toLanguageTag()
    val definitions = manifest?.filters?.sortedWith(compareBy({ it.order }, { it.id })).orEmpty()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.filters_title)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (definitions.isEmpty()) item { Text(stringResource(R.string.filters_all)) }
                items(definitions, key = { it.id }) { definition ->
                    val field = manifest?.fields?.firstOrNull { it.id == definition.field }
                    if (field != null) {
                        val selected = draft.selections[definition.id]
                        val title = definition.labels[languageTag]
                            ?: definition.labels[languageTag.substringBefore('-')]
                            ?: definition.labels["en"]
                            ?: field.labels[languageTag]
                            ?: field.labels[languageTag.substringBefore('-')]
                            ?: field.labels["en"] ?: definition.id
                        val values = options[definition.id].orEmpty().map { it.filterLabel() }.distinct()
                            .ifEmpty { field.options.toList().sorted() }
                        val choice = selected as? CatalogFilterValue.Choices
                        FilterMultiChoice(values, choice?.values.orEmpty(), choice?.matchAll == true, title) { updated, matchAll ->
                            draft = draft.withFilter(definition.id,
                                updated.takeIf { it.isNotEmpty() }?.let { CatalogFilterValue.Choices(it, matchAll) })
                        }
                    }
                }
                item { TextButton(onClick = { draft = CatalogFilters() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.filters_clear))
                } }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft) }) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

private fun CatalogAttributeValue.filterLabel(): String = when (this) {
    is CatalogAttributeValue.Text -> value
    is CatalogAttributeValue.Number -> java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    is CatalogAttributeValue.Flag -> value.toString()
}

/** No arbitrary first-150 cap: search and lazy rendering for large pack vocabularies. */
@Composable
private fun FilterMultiChoice(options: List<String>, selected: Set<String>, matchAll: Boolean,
    title: String, onSelected: (Set<String>, Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.Start) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Text((if (options.isEmpty()) selected.sorted() else options.filter { it in selected })
                .takeIf { it.isNotEmpty() }?.joinToString()
                ?: stringResource(R.string.filters_all),
                maxLines = 1)
        }
        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
    }
    if (expanded) {
        var query by remember(title) { mutableStateOf("") }
        var pending by remember(title) { mutableStateOf(selected) }
        var pendingMatchAll by remember(title) { mutableStateOf(matchAll) }
        val modeDescription = stringResource(if (pendingMatchAll) R.string.filter_match_all_description
            else R.string.filter_match_any_description)
        val filtered = remember(options, query) {
            val term = query.trim()
            if (term.isEmpty()) options else options.filter { it.contains(term, ignoreCase = true) }
        }
        AlertDialog(onDismissRequest = { expanded = false },
            title = { Text(title) },
            text = { Column {
                OutlinedTextField(query, { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.filter_search_values)) })
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    item { Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { pending = emptySet() }, modifier = Modifier.weight(3f)) {
                            Text(stringResource(R.string.filters_all))
                        }
                        OutlinedButton(onClick = { pendingMatchAll = !pendingMatchAll },
                            modifier = Modifier.weight(1f).semantics { contentDescription = modeDescription },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (pendingMatchAll) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                contentColor = if (pendingMatchAll) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.primary
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text(if (pendingMatchAll) "AND" else "OR")
                        }
                    } }
                    items(filtered, key = { it }) { option ->
                        TextButton(onClick = {
                            pending = if (option in pending) pending - option else pending + option
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(option, modifier = Modifier.weight(1f))
                            Checkbox(option in pending, onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = if (pendingMatchAll)
                                    MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { onSelected(pending, pendingMatchAll); expanded = false }) {
                Text(stringResource(R.string.action_apply))
            } },
            dismissButton = { TextButton(onClick = { expanded = false }) {
                Text(stringResource(R.string.action_cancel))
            } })
    }
}
