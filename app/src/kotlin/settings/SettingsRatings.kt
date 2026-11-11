package org.rankoffline.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import java.util.UUID

@Composable
internal fun RatingSettingsScreen(
    elaborateRatingEnabled: Boolean,
    onElaborateRatingEnabledChange: (Boolean) -> Unit,
    personalTasteEnabled: Boolean,
    onPersonalTasteEnabledChange: (Boolean) -> Unit,
    onProfiles: () -> Unit,
    onCategories: () -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.settings_rating_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = floatingNavigationContentPadding(bottom = 24.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            stringResource(R.string.rating_mode),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        listOf(
                            false to stringResource(R.string.rating_simple),
                            true to stringResource(R.string.rating_elaborate)
                        ).forEach { (isElaborate, label) ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onElaborateRatingEnabledChange(isElaborate)
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = elaborateRatingEnabled == isElaborate,
                                    onClick = { onElaborateRatingEnabledChange(isElaborate) }
                                )
                                Text(label)
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.bonus_personal_taste),
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text(stringResource(R.string.personal_taste_setting_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = personalTasteEnabled,
                                onCheckedChange = onPersonalTasteEnabledChange)
                        }
                    }
                }
            }
            item {
                SettingsMenuItem(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.rating_profiles),
                    description = stringResource(R.string.rating_profiles_description),
                    onClick = onProfiles
                )
            }
            item {
                SettingsMenuItem(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = stringResource(R.string.rating_categories),
                    description = stringResource(R.string.rating_categories_description),
                    onClick = onCategories
                )
            }
        }
    }
}

@Composable
internal fun RatingCategoriesScreen(
    ratingConfig: RatingSystemConfig,
    onUpdate: (RatingCategory) -> Unit,
    onCreate: (RatingCategory) -> Unit,
    onDeleteCategory: (RatingCategory) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    var confirmReset by remember { mutableStateOf(false) }
    val newCategoryName = stringResource(R.string.rating_new_category_name)
    val newCategoryDescription = stringResource(R.string.rating_new_category_description)
    SettingsSubscreen(stringResource(R.string.rating_categories), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = floatingNavigationContentPadding(bottom = 24.dp)
        ) {
            items(ratingConfig.categories.sortedBy { it.order }, key = { it.id }) { category ->
                RatingCategoryEditor(category, onUpdate, onDelete = { onDeleteCategory(category) })
            }
            item {
                SettingsActionButton(
                    onClick = {
                        onCreate(
                            RatingCategory(
                                id = "custom_${UUID.randomUUID().toString().replace("-", "")}",
                                name = newCategoryName,
                                description = newCategoryDescription,
                                weight = 0.0,
                                color = "#7EC7FF",
                                order = (ratingConfig.categories.maxOfOrNull { it.order } ?: -1) + 1
                            )
                        )
                    },
                    label = stringResource(R.string.rating_add_category),
                    icon = Icons.Default.Add,
                    primary = true
                )
            }
            item {
                SettingsActionButton(
                    onClick = { confirmReset = true },
                    label = stringResource(R.string.rating_reset_categories),
                    icon = Icons.Default.Restore,
                    primary = false
                )
            }
        }
    }
    if (confirmReset) {
        ConfirmationDialog(
            title = stringResource(R.string.rating_reset_categories),
            text = stringResource(R.string.rating_reset_categories_confirmation),
            onDismiss = { confirmReset = false },
            onConfirm = { confirmReset = false; onReset() }
        )
    }
}

@Composable
private fun RatingCategoryEditor(category: RatingCategory, onUpdate: (RatingCategory) -> Unit, onDelete: () -> Unit) {
    val themePrimary = MaterialTheme.colorScheme.primary
    val themeSecondary = MaterialTheme.colorScheme.secondary
    val swatch = try {
        Color(android.graphics.Color.parseColor(category.color))
    } catch (_: Exception) {
        MaterialTheme.colorScheme.surface
    }
    var showColorPicker by remember(category.id) { mutableStateOf(false) }
    var showDeleteConfirmation by remember(category.id) { mutableStateOf(false) }
    val colorContentDescription = stringResource(R.string.category_color_content_description)

    Card(
        colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.92f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = localizedCategoryName(category),
                    onValueChange = { onUpdate(category.copy(name = it)) },
                    label = { Text(stringResource(R.string.category_name)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(onClick = { showDeleteConfirmation = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            OutlinedTextField(
                value = localizedCategoryDescription(category),
                onValueChange = { onUpdate(category.copy(description = it)) },
                label = { Text(stringResource(R.string.category_description)) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.category_color),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(52.dp)
                )
                ThemeColorShortcut(
                    label = stringResource(R.string.category_color_primary),
                    color = themePrimary,
                    onClick = {
                        onUpdate(category.copy(color = HsvColor.fromArgb(themePrimary.toArgb()).toHex()))
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                ThemeColorShortcut(
                    label = stringResource(R.string.category_color_secondary),
                    color = themeSecondary,
                    onClick = {
                        onUpdate(category.copy(color = HsvColor.fromArgb(themeSecondary.toArgb()).toHex()))
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(swatch)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .clickable { showColorPicker = true }
                        .semantics {
                            contentDescription = colorContentDescription
                        }
                )
            }
        }
    }
    if (showColorPicker) {
        RankOfflineColorPickerDialog(
            initialColor = category.color,
            onDismiss = { showColorPicker = false },
            onApply = { color ->
                normalizeHexColor(color)?.let { onUpdate(category.copy(color = it)) }
                showColorPicker = false
            }
        )
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.category_delete_title)) },
            text = {
                Text(stringResource(R.string.category_delete_explanation, localizedCategoryName(category)))
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

@Composable
private fun ThemeColorShortcut(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clickable(role = Role.Button, onClick = onClick),
        color = color,
        contentColor = bestContentColor(color),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(10.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
