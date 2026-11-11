package org.rankoffline.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import java.math.BigDecimal

@Composable
internal fun RatingScoreHeader(base: Double?, bonus: Double, final: Double?, config: RatingSystemConfig,
    group: Boolean, showPersonalTaste: Boolean, onEdit: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(stringResource(if (group) R.string.score_group_title else R.string.score_title),
                    style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.score_quality, base?.let(::formatScoreForDisplay) ?: "—"))
                if (showPersonalTaste) {
                    val bonusConfig = config.additiveBonus
                    Text(stringResource(R.string.score_bonus_value, localizedBonusName(bonusConfig),
                        formatScoreForDisplay(bonus)),
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
            ScoreValueBadge(final, onEdit)
        }
    }
}

@Composable
internal fun RatingProfileSelector(profiles: List<RatingProfile>, selected: RatingProfile,
    onSelect: (RatingProfile) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Card(colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth().testTag("rating_profile_selector")) {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.rating_profile),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Text(localizedProfileName(selected), fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ArrowDropDown,
                    contentDescription = stringResource(R.string.rating_profile),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        RankOfflinePopupMenu(expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 240.dp, max = 360.dp)
                .testTag("rating_profile_list")) {
            profiles.forEach { profile ->
                RankOfflinePopupMenuItem(label = localizedProfileName(profile),
                    selected = profile.id == selected.id,
                    onClick = { onSelect(profile); open = false },
                    modifier = Modifier.testTag("rating_profile_${profile.id}"))
            }
        }
    }
}

@Composable
internal fun RatingCategorySlider(category: RatingCategory, score: Double?, onChange: (Double?) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(localizedCategoryName(category), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onChange(if (score == null) 5.0 else null) }) {
                    Text(stringResource(if (score == null) R.string.score_activate_category else R.string.score_not_applicable))
                }
            }
            Text(localizedCategoryDescription(category), style = MaterialTheme.typography.bodySmall)
            if (score != null) {
                Text(stringResource(R.string.score_out_of_ten, formatScoreForDisplay(score)))
                DiscreteSlider(score, { onChange(it) }, RATING_MIN, RATING_MAX, RATING_STEP,
                    localizedCategoryName(category), stringResource(R.string.score_out_of_ten,
                        formatScoreForDisplay(score)))
            }
        }
    }
}

@Composable
internal fun RatingBonusSlider(bonus: AdditiveBonus, value: Double, onChange: (Double) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.bonus_range, localizedBonusName(bonus), bonus.maxValue))
            DiscreteSlider(value, onChange, BONUS_MIN, bonus.maxValue, BONUS_STEP,
                localizedBonusName(bonus), stringResource(R.string.bonus_current,
                    formatScoreForDisplay(value)))
        }
    }
}

@Composable
internal fun RatingDirectScoreDialog(current: Double?, onDismiss: () -> Unit,
    onApply: (Double) -> Unit) {
    var input by remember(current) {
        mutableStateOf(current?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty())
    }
    val parsed = parseDirectRatingValue(input)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.score_direct_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.score_direct_explanation))
            OutlinedTextField(input, { input = it }, singleLine = true,
                label = { Text(stringResource(R.string.score_direct_label)) },
                isError = input.isNotBlank() && parsed == null)
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let(onApply) }) {
            Text(stringResource(R.string.action_apply))
        } })
}
