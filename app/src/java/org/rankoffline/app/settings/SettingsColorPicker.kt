package org.rankoffline.app

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*

@Composable
internal fun RankOfflineColorPickerDialog(
    initialColor: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val initialHsv = remember(initialColor) {
        HsvColor.fromHex(initialColor) ?: HsvColor.fromHex("#19D5E5")!!
    }
    var hsv by remember(initialColor) { mutableStateOf(initialHsv) }
    var hexInput by remember(initialColor) { mutableStateOf(initialHsv.toHex()) }
    val validColor = normalizeHexColor(hexInput)
    val previewColor = Color(hsv.toArgb())
    val saturationValueDescription = stringResource(R.string.color_picker_saturation_value)
    val hueDescription = stringResource(R.string.color_picker_hue)

    fun updateFromHsv(value: HsvColor) {
        hsv = value.normalized()
        hexInput = hsv.toHex()
    }

    AlertDialog(
        modifier = Modifier.clearFocusOnBackgroundTap(focusManager),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.color_picker_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(previewColor, RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                )
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .testTag("color_picker_saturation_value")
                        .aspectRatio(1.55f)
                        .clip(RoundedCornerShape(12.dp))
                        .semantics { contentDescription = saturationValueDescription }
                        .pointerInput(hsv.hue) {
                            fun select(position: Offset) {
                                updateFromHsv(
                                    hsv.copy(
                                        saturation = (position.x / size.width).coerceIn(0f, 1f),
                                        value = (1f - position.y / size.height).coerceIn(0f, 1f)
                                    )
                                )
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                select(down.position)
                                drag(down.id) { change ->
                                    select(change.position)
                                    change.consume()
                                }
                            }
                        }
                ) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.White, Color.hsv(hsv.hue, 1f, 1f))
                        )
                    )
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                    )
                    val marker = Offset(hsv.saturation * size.width, (1f - hsv.value) * size.height)
                    drawCircle(bestContentColor(previewColor), radius = 10.dp.toPx(), center = marker)
                    drawCircle(previewColor, radius = 7.dp.toPx(), center = marker)
                }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .testTag("color_picker_hue")
                        .height(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .semantics { contentDescription = hueDescription }
                        .pointerInput(Unit) {
                            fun select(position: Offset) {
                                updateFromHsv(hsv.copy(hue = position.x / size.width * 360f))
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                select(down.position)
                                drag(down.id) { change ->
                                    select(change.position)
                                    change.consume()
                                }
                            }
                        }
                ) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color.Red,
                                Color.Yellow,
                                Color.Green,
                                Color.Cyan,
                                Color.Blue,
                                Color.Magenta,
                                Color.Red
                            )
                        )
                    )
                    val marker = Offset(hsv.hue / 360f * size.width, size.height / 2f)
                    drawCircle(bestContentColor(Color.hsv(hsv.hue, 1f, 1f)), 9.dp.toPx(), marker)
                    drawCircle(Color.hsv(hsv.hue, 1f, 1f), 6.dp.toPx(), marker)
                }
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { value ->
                        hexInput = value
                        HsvColor.fromHex(value)?.let { hsv = it }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("color_picker_hex"),
                    label = { Text(stringResource(R.string.color_hex_label)) },
                    supportingText = {
                        if (hexInput.isNotBlank() && validColor == null) {
                            Text(stringResource(R.string.color_invalid))
                        }
                    },
                    isError = hexInput.isNotBlank() && validColor == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { focusManager.clearFocus(force = true) }
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = validColor != null,
                onClick = { validColor?.let { onApply(hsv.toHex()) } }
            ) { Text(stringResource(R.string.action_apply)) }
        }
    )
}
