package org.rankoffline.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource

@Composable
fun MediaScreenTitle(
    title: String,
    activeMediaType: MediaType,
    onMediaTypeSelected: (MediaType) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val titleStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = titleStyle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Box {
            Row(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(role = Role.Button) { expanded = true }
                    .padding(start = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(activeMediaType.labelResource),
                    style = titleStyle,
                    color = activeMediaType.accentColor,
                    maxLines = 1,
                    softWrap = false
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = activeMediaType.accentColor
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                MediaType.entries.forEach { mediaType ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(mediaType.labelResource),
                                color = mediaType.accentColor,
                                fontWeight = if (mediaType == activeMediaType) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                }
                            )
                        },
                        trailingIcon = if (mediaType == activeMediaType) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = mediaType.accentColor
                                )
                            }
                        } else {
                            null
                        },
                        onClick = {
                            expanded = false
                            if (mediaType != activeMediaType) onMediaTypeSelected(mediaType)
                        }
                    )
                }
            }
        }
    }
}
