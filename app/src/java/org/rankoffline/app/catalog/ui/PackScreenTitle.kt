package org.rankoffline.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.Collator
import java.util.Locale

/** Only display information: no catalog/Room/registry queries during composition. */
internal data class PackScreenOption(
    val packId: PackId,
    val name: String,
    val accentArgb: Int?
)

/** Resolve labels and deterministic locale-sensitive ordering from installed manifests. */
internal fun packScreenOptions(
    installedManifests: List<PackManifest>,
    languageTag: String
): List<PackScreenOption> {
    val locale = Locale.forLanguageTag(languageTag)
    val collator = Collator.getInstance(locale)
    return installedManifests.distinctBy(PackManifest::packId)
        .map { manifest ->
            PackScreenOption(
                packId = manifest.packId,
                name = manifest.localizedName(languageTag).trim()
                    .ifEmpty { manifest.displayName },
                accentArgb = packAccentArgbOrNull(manifest.presentation.accentColor)
            )
        }
        .sortedWith { left, right ->
            val labelOrder = collator.compare(left.name, right.name)
            if (labelOrder != 0) labelOrder else left.packId.value.compareTo(right.packId.value)
        }
}


/**
 * Header and installed-pack selector for Search, Ranking and Settings.
 *
 * The caller supplies *only installed* manifests, the selected PackId and callbacks.
 * This component performs no file IO, package discovery, downloads or selection persistence.
 */
@Composable
fun PackScreenTitle(
    title: String,
    installedManifests: List<PackManifest>,
    activePackId: PackId?,
    onPackSelected: (PackId) -> Unit,
    onManagePacks: () -> Unit,
    modifier: Modifier = Modifier,
    allSelected: Boolean = false,
    allowAll: Boolean = true,
    onAllSelected: () -> Unit = {}
) {
    val languageTag = LocalConfiguration.current.locales[0]?.toLanguageTag()
        ?: Locale.getDefault().toLanguageTag()
    val options = remember(installedManifests, languageTag) {
        packScreenOptions(installedManifests, languageTag)
    }
    val current = options.firstOrNull { it.packId == activePackId }
    val onBackground = MaterialTheme.colorScheme.onBackground
    val accent = current?.accentArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val showAll = allowAll && (options.size > 1 || allSelected)
    val currentLabel = when {
        allSelected && showAll -> stringResource(R.string.media_all)
        current != null -> current.name
        else -> null
    }
    val titleColor = if (allSelected) onBackground else accent
    var expanded by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .testTag("pack_screen_title")
            .clickable(role = Role.Button) { expanded = true }
            .minimumInteractiveComponentSize()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = currentLabel?.let { mediaScreenTitle(title, it, titleColor) }
                ?: AnnotatedString(title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = if (currentLabel == null || allSelected) onBackground else accent
        )
    }

    if (expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 500.dp)
            ) {
                BoxWithConstraints {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = maxHeight * 0.85f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                    ) {
                        item(key = "heading") {
                            Text(
                                text = stringResource(R.string.app_name),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                            )
                        }
                        if (showAll) {
                            item(key = "all") {
                                PackScreenChoice(
                                    text = stringResource(R.string.media_all),
                                    selected = allSelected,
                                    accent = MaterialTheme.colorScheme.primary,
                                    testTag = "pack_all",
                                    onClick = {
                                        expanded = false
                                        if (!allSelected) onAllSelected()
                                    }
                                )
                            }
                        }
                        items(options, key = { "pack_${it.packId.value}" }) { option ->
                            val selected = !allSelected && option.packId == activePackId
                            PackScreenChoice(
                                text = option.name,
                                selected = selected,
                                accent = option.accentArgb?.let { Color(it) }
                                    ?: MaterialTheme.colorScheme.primary,
                                testTag = "pack_${option.packId.value}",
                                onClick = {
                                    expanded = false
                                    if (!selected) onPackSelected(option.packId)
                                }
                            )
                        }
                        item(key = "manage") {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .testTag("pack_manage")
                                    .clickable(role = Role.Button) {
                                        expanded = false
                                        onManagePacks()
                                    }
                                    .minimumInteractiveComponentSize()
                                    .padding(horizontal = 14.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.download_pack),
                                    fontSize = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = null
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackScreenChoice(
    text: String,
    selected: Boolean,
    accent: Color,
    testTag: String,
    onClick: () -> Unit
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .testTag(testTag)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .minimumInteractiveComponentSize()
            .background(if (selected) accent.copy(alpha = 0.12f) else surface)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = accent)
    }
}

/** Shared title formatting; also used by the pre-existing onboarding screen. */
internal fun mediaScreenTitle(title: String, mediaLabel: String, accentColor: Color): AnnotatedString =
    buildAnnotatedString {
        append(title.trimEnd())
        if (title.isNotBlank() && mediaLabel.isNotBlank()) append(" · ")
        withStyle(SpanStyle(color = accentColor)) { append(mediaLabel) }
    }
