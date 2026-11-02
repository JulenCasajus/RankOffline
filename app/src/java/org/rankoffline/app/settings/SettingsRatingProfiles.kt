package org.rankoffline.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import java.util.UUID

@Composable
internal fun RatingProfilesScreen(
    profilesConfig: RatingProfilesConfig,
    categories: List<RatingCategory>,
    onUpdate: (RatingProfilesConfig) -> Unit,
    onDelete: (String) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    var confirmReset by remember { mutableStateOf(false) }
    val newProfileName = stringResource(R.string.rating_new_profile_name)
    SettingsSubscreen(stringResource(R.string.rating_profiles), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = floatingNavigationContentPadding(bottom = 24.dp)
        ) {
            items(profilesConfig.profiles, key = { it.id }) { profile ->
                RatingProfileEditor(
                    profile = profile,
                    categories = categories.sortedBy { it.order },
                    canDelete = profilesConfig.profiles.size > 1,
                    onUpdate = { changed ->
                        onUpdate(profilesConfig.copy(profiles = profilesConfig.profiles.map {
                            if (it.id == changed.id) changed else it
                        }))
                    },
                    onDelete = { onDelete(profile.id) }
                )
            }
            item {
                SettingsActionButton(
                    onClick = {
                        val profile = RatingProfile(
                            id = "custom_${UUID.randomUUID()}",
                            name = newProfileName,
                            weights = categories.associate { it.id to 5.0 }
                        )
                        onUpdate(profilesConfig.copy(profiles = profilesConfig.profiles + profile))
                    },
                    label = stringResource(R.string.rating_create_profile),
                    icon = Icons.Default.Add,
                    filled = true
                )
            }
            item {
                SettingsActionButton(
                    onClick = { confirmReset = true },
                    label = stringResource(R.string.rating_reset_profiles),
                    icon = Icons.Default.Restore,
                    filled = false
                )
            }
        }
    }
    if (confirmReset) {
        ConfirmationDialog(
            title = stringResource(R.string.rating_reset_profiles),
            text = stringResource(R.string.rating_reset_profiles_confirmation),
            onDismiss = { confirmReset = false },
            onConfirm = { confirmReset = false; onReset() }
        )
    }
}

@Composable
internal fun RatingProfileEditor(
    profile: RatingProfile,
    categories: List<RatingCategory>,
    canDelete: Boolean,
    onUpdate: (RatingProfile) -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirmation by remember(profile.id) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = localizedProfileName(profile),
                    onValueChange = { onUpdate(profile.copy(name = it)) },
                    label = { Text(stringResource(R.string.rating_profile_name)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(enabled = canDelete, onClick = { showDeleteConfirmation = true }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.rating_delete_profile), tint = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            categories.forEach { category ->
                val weight = profile.weights[category.id] ?: 0.0
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(localizedCategoryName(category), modifier = Modifier.width(100.dp), maxLines = 1)
                    DiscreteSlider(
                        value = weight,
                        onValueChange = { value ->
                            snapWeight(value)?.let { onUpdate(profile.copy(weights = profile.weights + (category.id to it))) }
                        },
                        min = WEIGHT_MIN,
                        max = WEIGHT_MAX,
                        step = WEIGHT_STEP,
                        accessibilityLabel = stringResource(R.string.category_weight_slider_accessibility),
                        valueDescription = stringResource(R.string.category_weight_percent, weight.toInt()),
                        modifier = Modifier.weight(1f)
                    )
                    Text(stringResource(R.string.category_weight_percent, weight.toInt()), modifier = Modifier.width(52.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            if (!canDelete) {
                Text(stringResource(R.string.rating_last_profile_required), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
    if (showDeleteConfirmation) {
        ConfirmationDialog(
            title = stringResource(R.string.rating_delete_profile),
            text = stringResource(R.string.rating_delete_profile_confirmation, localizedProfileName(profile)),
            onDismiss = { showDeleteConfirmation = false },
            onConfirm = { showDeleteConfirmation = false; onDelete() }
        )
    }
}
