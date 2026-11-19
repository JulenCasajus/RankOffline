package org.rankoffline.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** List and detail screens use the same decision and the same editor choice. */
internal fun needsGroupRatingChoice(
    hasExplicitScore: Boolean, ratedMembers: Int, derivedScore: Double?
): Boolean = !hasExplicitScore && ratedMembers > 0 && derivedScore?.isFinite() == true

internal fun MediaListEntry.Group.needsGroupRatingChoice(): Boolean =
    needsGroupRatingChoice(!derived, ratedCount, finalScore)

@Composable
internal fun GroupRatingChoiceDialog(
    onDismiss: () -> Unit, onUseMembers: () -> Unit, onStartFresh: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_rate_together)) },
        text = { Text(stringResource(R.string.group_draft_choice)) },
        confirmButton = { TextButton(onClick = onUseMembers) {
            Text(stringResource(R.string.group_use_member_ratings))
        } },
        dismissButton = { TextButton(onClick = onStartFresh) {
            Text(stringResource(R.string.group_start_fresh))
        } }
    )
}
