package org.rankoffline.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

/** One editor for ITEM or GROUP; scoring and writes remain in RatingRepository. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RatingScreen(
    editor: RatingEditorState, draft: RatingDraft, settings: AppSettingsState,
    onBack: () -> Unit,
    onSaveSimple: (RatingDraft, Double, Double) -> Unit,
    onSaveElaborate: (RatingDraft, String, Double?) -> Unit,
    onSaveMetadata: (String, String) -> Unit,
    onDelete: () -> Unit
) {
    BackHandler(onBack = onBack)
    val packSettings = settings.mediaSettings[editor.key.packId]
    val configuration = packSettings?.ratingConfig ?: RatingSystemConfig(emptyList())
    val profiles = packSettings?.ratingProfiles ?: RatingProfilesConfig(emptyList())
    val elaborate = packSettings?.elaborateRatingEnabled == true && configuration.categories.isNotEmpty()
    val personalTasteEnabled = packSettings?.personalTasteEnabled == true
    val selected = profiles.profiles.firstOrNull { it.id == draft.profileId }
        ?: profiles.profiles.firstOrNull { it.id == "general" }
        ?: profiles.profiles.firstOrNull()
        ?: RatingProfile("general", "General", emptyMap())
    val identity = editor.key
    var selectedProfileId by rememberSaveable(identity) { mutableStateOf(selected.id) }
    val profile = profiles.profiles.firstOrNull { it.id == selectedProfileId } ?: selected
    val config = if (profiles.profiles.isEmpty()) configuration else configuration.withProfile(profile)
    var current by rememberSaveable(identity) {
        mutableStateOf(if (elaborate) draft.withDefaultCategoryScores(config) else draft)
    }
    var simpleQuality by rememberSaveable(identity) {
        mutableDoubleStateOf(snapRatingValue(
            draft.qualityScore ?: draft.finalScore?.minus(draft.additiveScore) ?: 5.0) ?: 5.0)
    }
    // Persisted final scores may intentionally differ from the current category calculation.
    // Editing notes/status must never erase such a manual score.
    val initialCalculated = if (elaborate) current.calculatedFinalScore(config)
        else finalScoreFromQuality(simpleQuality, current.additiveScore)
    var manualOverride by rememberSaveable(identity) {
        mutableStateOf(draft.finalScore?.takeIf { saved ->
            initialCalculated == null || kotlin.math.abs(saved - initialCalculated) > 0.0001
        })
    }
    var scoringChanged by rememberSaveable(identity) { mutableStateOf(false) }
    var showDirect by rememberSaveable(identity) { mutableStateOf(false) }
    val scoredDraft = current.forPersonalTaste(personalTasteEnabled)
    val base = if (elaborate) scoredDraft.baseScore(config) else simpleQuality
    val calculated = if (!scoringChanged && draft.finalScore != null) draft.finalScore
        else manualOverride ?: if (elaborate) scoredDraft.calculatedFinalScore(config)
            else finalScoreFromQuality(simpleQuality, scoredDraft.additiveScore)
    Scaffold(topBar = { RankOfflineTopAppBar(editor.entry.title, onBack) }, containerColor = Bg) { inset ->
        LazyColumn(Modifier.fillMaxSize().padding(inset).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 36.dp)) {
            val sticky = packSettings?.stickyScore != false
            if (sticky) stickyHeader { RatingScoreHeader(base, scoredDraft.additiveScore, calculated,
                configuration, editor.isGroup, personalTasteEnabled, { showDirect = true }) }
            else item { RatingScoreHeader(base, scoredDraft.additiveScore, calculated,
                configuration, editor.isGroup, personalTasteEnabled, { showDirect = true }) }
            if (elaborate) {
                if (profiles.profiles.isNotEmpty()) item { RatingProfileSelector(profiles.profiles, profile) {
                    selectedProfileId = it.id; manualOverride = null; scoringChanged = true
                } }
                items(config.categories.filter { it.active }.sortedBy { it.order }, key = { it.id }) { category ->
                    RatingCategorySlider(category, current.categoryScores[category.id]) {
                        current = current.copy(categoryScores = current.categoryScores + (category.id to it))
                        manualOverride = null; scoringChanged = true
                    }
                }
            } else item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.rating_simple_instructions))
                        Text(stringResource(R.string.score_out_of_ten,
                            formatScoreForDisplay(simpleQuality)),
                            style = MaterialTheme.typography.headlineSmall)
                        DiscreteSlider(simpleQuality, {
                            simpleQuality = it; manualOverride = null; scoringChanged = true
                        },
                            RATING_MIN, RATING_MAX, RATING_STEP,
                            stringResource(R.string.rating_slider_accessibility),
                            stringResource(R.string.score_out_of_ten,
                                formatScoreForDisplay(simpleQuality)))
                    }
                }
            }
            if (personalTasteEnabled) item {
                RatingBonusSlider(configuration.additiveBonus, current.additiveScore) {
                    current = current.copy(additiveScore = it)
                    manualOverride = null; scoringChanged = true
                }
            }
            item { OutlinedTextField(current.notes, { current = current.copy(notes = it) },
                label = { Text(stringResource(R.string.rating_notes)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        val metadataOnly = !scoringChanged &&
                            (current.notes != draft.notes || current.status != draft.status)
                        if (metadataOnly || (!scoringChanged && draft.finalScore != null))
                            onSaveMetadata(current.notes, current.status)
                        else if (elaborate) onSaveElaborate(scoredDraft, profile.id, manualOverride)
                        else calculated?.let { onSaveSimple(scoredDraft, simpleQuality, it) }
                    }, modifier = Modifier.weight(1f), enabled = calculated != null) {
                        Icon(Icons.Default.Save, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_save))
                    }
                    if (draft.finalScore != null || draft.qualityScore != null || draft.notes.isNotEmpty()) {
                        OutlinedButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, null)
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
    }
    if (showDirect) RatingDirectScoreDialog(calculated, onDismiss = { showDirect = false },
        onApply = { manualOverride = it; scoringChanged = true; showDirect = false })
}
