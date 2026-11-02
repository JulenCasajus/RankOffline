package org.rankoffline.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
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
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 510.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, style = MaterialTheme.typography.titleSmall)
                            when (definition.type) {
                                PackFilterType.MULTI_SELECT -> when (field.type) {
                                    CatalogFieldType.BOOLEAN -> {
                                        Row(Modifier.fillMaxWidth()) {
                                            listOf(true, false).forEach { candidate ->
                                                FilterChip(selected = (selected as? CatalogFilterValue.BooleanEquals)?.value == candidate,
                                                    onClick = {
                                                        draft = draft.withFilter(definition.id,
                                                            if ((selected as? CatalogFilterValue.BooleanEquals)?.value == candidate) null
                                                            else CatalogFilterValue.BooleanEquals(candidate))
                                                    }, label = { Text(candidate.toString()) })
                                            }
                                        }
                                    }
                                    else -> {
                                        val values = options[definition.id].orEmpty().mapNotNull {
                                            (it as? CatalogAttributeValue.Text)?.value
                                        }.ifEmpty { field.options.toList().sorted() }
                                        val chosen = (selected as? CatalogFilterValue.Choices)?.values.orEmpty()
                                        FilterMultiChoice(values, chosen) { updated ->
                                            draft = draft.withFilter(definition.id,
                                                updated.takeIf { it.isNotEmpty() }?.let(CatalogFilterValue::Choices))
                                        }
                                    }
                                }
                                PackFilterType.RANGE -> when (field.type) {
                                    CatalogFieldType.DATE -> {
                                        val range = selected as? CatalogFilterValue.DateRange
                                        var from by remember(definition.id, range?.from) { mutableStateOf(range?.from.orEmpty()) }
                                        var to by remember(definition.id, range?.to) { mutableStateOf(range?.to.orEmpty()) }
                                        OutlinedTextField(from, { new -> from = new; draft = draft.withDateRange(definition.id, from, to) },
                                            label = { Text("From (YYYY-MM-DD)") }, singleLine = true)
                                        OutlinedTextField(to, { new -> to = new; draft = draft.withDateRange(definition.id, from, to) },
                                            label = { Text("To (YYYY-MM-DD)") }, singleLine = true)
                                    }
                                    else -> {
                                        val range = selected as? CatalogFilterValue.Range
                                        var from by remember(definition.id, range?.min) { mutableStateOf(range?.min?.toString().orEmpty()) }
                                        var to by remember(definition.id, range?.max) { mutableStateOf(range?.max?.toString().orEmpty()) }
                                        OutlinedTextField(from, { new -> from = new; draft = draft.withNumericRange(definition.id, from, to) },
                                            label = { Text("Min") }, singleLine = true)
                                        OutlinedTextField(to, { new -> to = new; draft = draft.withNumericRange(definition.id, from, to) },
                                            label = { Text("Max") }, singleLine = true)
                                    }
                                }
                            }
                        }
                    }
                }
                item { TextButton(onClick = { draft = CatalogFilters() }) {
                    Text(stringResource(R.string.filters_clear))
                } }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft) }) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}



/** No arbitrary first-150 cap: search and lazy rendering for large pack vocabularies. */
@Composable
private fun FilterMultiChoice(options: List<String>, selected: Set<String>,
    onSelected: (Set<String>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
        Text("${selected.size} selected · ${options.size} available")
    }
    if (expanded) {
        var query by remember { mutableStateOf("") }
        val filtered = remember(options, query) {
            val term = query.trim()
            if (term.isEmpty()) options else options.filter { it.contains(term, ignoreCase = true) }
        }
        AlertDialog(onDismissRequest = { expanded = false },
            title = { Text(stringResource(R.string.filters_title)) },
            text = { Column {
                OutlinedTextField(query, { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.filter_search_values)) })
                LazyColumn(Modifier.heightIn(max = 350.dp)) {
                    items(filtered, key = { it }) { option ->
                        Row(Modifier.fillMaxWidth()) {
                            Checkbox(option in selected, onCheckedChange = { checked ->
                                onSelected(if (checked) selected + option else selected - option)
                            })
                            Text(option, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { expanded = false }) {
                Text(stringResource(R.string.action_apply))
            } })
    }
}
