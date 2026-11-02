package org.rankoffline.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import java.math.BigDecimal

@Composable
internal fun RatingScoreHeader(base: Double?, bonus: Double, final: Double?, config: RatingSystemConfig,
    group: Boolean, onEdit: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(stringResource(if (group) R.string.score_group_title else R.string.score_title),
                    style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.score_quality, base?.toString() ?: "—"))
                val bonusConfig = config.additiveBonus
                Text(stringResource(R.string.score_bonus_value, localizedBonusName(bonusConfig), bonus),
                    color = MaterialTheme.colorScheme.secondary)
            }
            ScoreValueBadge(final, onEdit)
        }
    }
}

@Composable
internal fun RatingProfileSelector(profiles: List<RatingProfile>, selected: RatingProfile,
    onSelect: (RatingProfile) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(localizedProfileName(selected), modifier = Modifier.weight(1f))
        Icon(Icons.Default.ArrowDropDown, null)
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.rating_profile)) },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(profiles, key = { it.id }) { profile ->
                TextButton(onClick = { onSelect(profile); open = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(localizedProfileName(profile))
                }
            }
        } }, confirmButton = { TextButton(onClick = { open = false }) {
            Text(stringResource(R.string.action_cancel))
        } })
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
                Text(stringResource(R.string.score_out_of_ten, score))
                DiscreteSlider(score, { onChange(it) }, RATING_MIN, RATING_MAX, RATING_STEP,
                    localizedCategoryName(category), stringResource(R.string.score_out_of_ten, score))
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
                localizedBonusName(bonus), stringResource(R.string.bonus_current, value))
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
