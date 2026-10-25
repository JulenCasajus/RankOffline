package org.rankoffline.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.Collator
import java.util.Locale

@Composable
fun MediaScreenTitle(
    title: String,
    activeMediaType: MediaType,
    onMediaTypeSelected: (MediaType) -> Unit,
    modifier: Modifier = Modifier,
    availableMediaTypes: Set<MediaType> = MediaType.entries.toSet(),
    onDownloadPack: () -> Unit,
    noPacksInstalled: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val titleStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)
    val appName = stringResource(R.string.app_name)
    val localizedLabels = MediaType.entries.associateWith { stringResource(it.labelResource) }
    val menuOptions = installedMediaTypeMenuOptions(
        localizedLabels,
        Locale.getDefault(),
        availableMediaTypes
    )

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .testTag("media_screen_title")
                .clickable(role = Role.Button) { expanded = true }
                .minimumInteractiveComponentSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (noPacksInstalled) AnnotatedString(title) else mediaScreenTitle(
                    title, localizedLabels.getValue(activeMediaType), activeMediaType.accentColor
                ),
                style = titleStyle,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = if (noPacksInstalled) MaterialTheme.colorScheme.onBackground
                else activeMediaType.accentColor
            )
        }
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
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.category_selector_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                    )
                    menuOptions.forEach { mediaType ->
                        val selected = mediaType == activeMediaType
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) mediaType.accentColor.copy(alpha = 0.14f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("media_pack_${mediaType.persistedValue}")
                                .padding(vertical = 4.dp)
                                .clickable(role = Role.Button) {
                                    expanded = false
                                    onMediaTypeSelected(mediaType)
                                }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = mediaScreenTitle(
                                        appName,
                                        localizedLabels.getValue(mediaType),
                                        mediaType.accentColor
                                    ),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 18.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Spacer(Modifier.weight(1f))
                                when {
                                    selected -> Icon(Icons.Default.Check, contentDescription = null, tint = mediaType.accentColor)
                                }
                            }
                        }
                    }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("media_download_pack")
                            .padding(vertical = 4.dp)
                            .clickable(role = Role.Button) {
                                expanded = false
                                onDownloadPack()
                            }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.download_pack),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp
                            )
                            Spacer(Modifier.weight(1f))
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = stringResource(R.string.download_pack),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

internal enum class CategorySelectionAction { SWITCH, MANAGE_DOWNLOAD }

internal fun categorySelectionAction(
    mediaType: MediaType,
    availableMediaTypes: Set<MediaType>
): CategorySelectionAction = if (mediaType in availableMediaTypes) {
    CategorySelectionAction.SWITCH
} else CategorySelectionAction.MANAGE_DOWNLOAD

internal fun mediaTypeMenuOptions(
    localizedLabels: Map<MediaType, String>,
    locale: Locale
): List<MediaType> {
    val collator = Collator.getInstance(locale)
    return MediaType.entries
        .sortedWith { first, second ->
            collator.compare(localizedLabels.getValue(first), localizedLabels.getValue(second))
        }
}

internal fun installedMediaTypeMenuOptions(
    localizedLabels: Map<MediaType, String>,
    locale: Locale,
    selectableMediaTypes: Set<MediaType>
): List<MediaType> = mediaTypeMenuOptions(localizedLabels, locale)
    .filter(selectableMediaTypes::contains)

internal fun mediaScreenTitle(title: String, mediaLabel: String, accentColor: Color): AnnotatedString =
    buildAnnotatedString {
        append(title)
        withStyle(SpanStyle(color = accentColor)) {
            append(mediaLabel)
        }
    }
