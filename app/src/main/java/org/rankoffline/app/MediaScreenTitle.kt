package org.rankoffline.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
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
import java.text.Collator
import java.util.Locale

@Composable
fun MediaScreenTitle(
    title: String,
    activeMediaType: MediaType,
    onMediaTypeSelected: (MediaType) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val titleStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)
    val localizedLabels = MediaType.entries.associateWith { stringResource(it.labelResource) }
    val menuOptions = mediaTypeMenuOptions(activeMediaType, localizedLabels, Locale.getDefault())

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { expanded = true }
                .minimumInteractiveComponentSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = mediaScreenTitle(title, localizedLabels.getValue(activeMediaType), activeMediaType.accentColor),
                style = titleStyle,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = activeMediaType.accentColor
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menuOptions.forEach { mediaType ->
                DropdownMenuItem(
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(mediaType.accentColor)
                        )
                    },
                    text = {
                        Text(
                            text = mediaScreenTitle(title, localizedLabels.getValue(mediaType), mediaType.accentColor),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    onClick = {
                        expanded = false
                        onMediaTypeSelected(mediaType)
                    }
                )
            }
        }
    }
}

internal fun mediaTypeMenuOptions(
    activeMediaType: MediaType,
    localizedLabels: Map<MediaType, String>,
    locale: Locale
): List<MediaType> {
    val collator = Collator.getInstance(locale)
    return MediaType.entries
        .filterNot { it == activeMediaType }
        .sortedWith { first, second ->
            collator.compare(localizedLabels.getValue(first), localizedLabels.getValue(second))
        }
}

internal fun mediaScreenTitle(title: String, mediaLabel: String, accentColor: Color): AnnotatedString =
    buildAnnotatedString {
        append(title)
        withStyle(SpanStyle(color = accentColor)) {
            append(mediaLabel)
        }
    }
