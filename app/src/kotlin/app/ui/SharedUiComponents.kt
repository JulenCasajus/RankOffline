package org.rankoffline.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat

/** Keep these theme aliases in exactly one file: used by other existing components. */
internal val Bg: Color @Composable get() = MaterialTheme.colorScheme.background
internal val Panel: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val Cyan = Color(0xFF19D5E5)
internal val Magenta = Color(0xFFE04BCF)
internal val Coral = Color(0xFFFF675D)
internal val Orange = Color(0xFFFFAA55)
internal val Mint = Color(0xFF62D6C8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RankOfflineTopAppBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
        } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground)
    )
}

@Composable
internal fun EmptyPackScreen(title: String, manifests: List<PackManifest>, onManage: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        PackScreenTitle(title, manifests, null, {}, onManagePacks = onManage, allowAll = false)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.packs_empty_invitation),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp))
        }
    }
}

@Composable
internal fun ScoreValueBadge(score: Double?, onClick: (() -> Unit)? = null) {
    val background = score?.let(::scoreColor) ?: MaterialTheme.colorScheme.surfaceVariant
    Surface(onClick = { onClick?.invoke() }, enabled = onClick != null,
        shape = RoundedCornerShape(12.dp), color = background) {
        Text(score?.let(::formatScoreForDisplay) ?: "—",
            color = bestContentColor(background),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

/** Presentation only: never change the precision used for persistence or ranking. */
internal fun formatScoreForDisplay(value: Double, locale: Locale = Locale.getDefault()): String {
    if (!value.isFinite()) return "—"
    return NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        roundingMode = RoundingMode.DOWN
    }.format(BigDecimal.valueOf(value))
}

/** Shared slider between RatingScreen and SettingsRatingProfiles: no second implementation. */
@Composable
internal fun DiscreteSlider(value: Double, onValueChange: (Double) -> Unit,
    min: Double, max: Double, step: Double,
    accessibilityLabel: String, valueDescription: String,
    modifier: Modifier = Modifier) {
    val valid = snapDiscreteValue(value, min, max, step) ?: min
    val segments = ((max - min) / step).toInt().coerceAtLeast(1)
    Slider(
        value = valid.toFloat(),
        onValueChange = { raw -> snapDiscreteValue(raw.toDouble(), min, max, step)?.let(onValueChange) },
        valueRange = min.toFloat()..max.toFloat(),
        steps = (segments - 1).coerceAtLeast(0),
        modifier = modifier.semanticsWithRating(accessibilityLabel, valueDescription)
    )
}

private fun Modifier.semanticsWithRating(label: String, value: String): Modifier =
    this.semantics {
        contentDescription = label
        stateDescription = value
    }

/** Retain old public helper name for other UI modules, but pack-agnostic. */
internal fun sliderInactiveTrackColor(background: Color, surface: Color, onSurface: Color): Color =
    lerp(surface, onSurface, if (background.luminance() >= 0.5f) 0.12f else 0.24f)
