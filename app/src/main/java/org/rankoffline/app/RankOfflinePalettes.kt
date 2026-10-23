package org.rankoffline.app

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stable IDs for the official RankOffline palettes.
 *
 * Custom user palettes should NOT be added to this enum.
 * They can use [AppPalette] directly and be persisted separately.
 */
enum class AppThemePalette(
    val persistedValue: String,
    @StringRes val displayNameResource: Int
) {
    MIDNIGHT_NEON("midnight_neon", R.string.theme_midnight_neon),
    DEEP_OCEAN("deep_ocean", R.string.theme_deep_ocean),
    EMERALD_FOREST("emerald_forest", R.string.theme_emerald_forest),
    GOLDEN_SUNSET("golden_sunset", R.string.theme_golden_sunset),
    PINK_EXPLOSION("pink_explosion", R.string.theme_pink_explosion),
    CHERRY_BLOSSOM("cherry_blossom", R.string.theme_cherry_blossom),
    LAVENDER_DREAM("lavender_dream", R.string.theme_lavender_dream);

    companion object {
        fun fromPersistedValue(value: String?): AppThemePalette =
            entries.firstOrNull { it.persistedValue == value } ?: MIDNIGHT_NEON
    }
}

/**
 * Complete visual definition of a RankOffline palette.
 *
 * This model is intentionally independent from [AppThemePalette],
 * so it can also represent future user-created themes.
 */
data class AppPalette(
    val background: Color,
    val surface: Color,
    val primary: Color,
    val secondary: Color
)

data class CustomAppPalette(
    val id: String,
    val name: String,
    val palette: AppPalette
)

sealed interface ThemeSelection {
    data class BuiltIn(val palette: AppThemePalette) : ThemeSelection
    data class Custom(val id: String) : ThemeSelection

    val persistedValue: String
        get() = when (this) {
            is BuiltIn -> palette.persistedValue
            is Custom -> "$CUSTOM_THEME_PREFIX$id"
        }

    companion object {
        private const val CUSTOM_THEME_PREFIX = "custom:"

        fun fromPersistedValue(
            value: String?,
            customPalettes: List<CustomAppPalette>
        ): ThemeSelection {
            if (value?.startsWith(CUSTOM_THEME_PREFIX) == true) {
                val id = value.removePrefix(CUSTOM_THEME_PREFIX)
                if (customPalettes.any { it.id == id }) return Custom(id)
                return BuiltIn(AppThemePalette.MIDNIGHT_NEON)
            }
            return BuiltIn(AppThemePalette.fromPersistedValue(value))
        }
    }
}

internal fun ThemeSelection.resolve(customPalettes: List<CustomAppPalette>): AppPalette = when (this) {
    is ThemeSelection.BuiltIn -> palette.palette()
    is ThemeSelection.Custom -> customPalettes.firstOrNull { it.id == id }?.palette
        ?: AppThemePalette.MIDNIGHT_NEON.palette()
}

internal fun ThemeSelection.resolve(
    customPalettes: List<CustomAppPalette>,
    builtInOverrides: Map<AppThemePalette, CustomAppPalette>
): AppPalette = when (this) {
    is ThemeSelection.BuiltIn -> builtInOverrides[palette]?.palette ?: palette.palette()
    is ThemeSelection.Custom -> customPalettes.firstOrNull { it.id == id }?.palette
        ?: AppThemePalette.MIDNIGHT_NEON.palette()
}

data class ThemeWorkspace(
    val customPalettes: List<CustomAppPalette> = emptyList(),
    val builtInOverrides: Map<AppThemePalette, CustomAppPalette> = emptyMap(),
    val hiddenBuiltIns: Set<AppThemePalette> = emptySet()
)

internal fun List<CustomAppPalette>.upsert(value: CustomAppPalette): List<CustomAppPalette> {
    val normalized = value.copy(name = value.name.trim())
    require(normalized.id.matches(CUSTOM_THEME_ID_PATTERN))
    require(normalized.name.isNotEmpty() && normalized.name.length <= MAX_CUSTOM_THEME_NAME_LENGTH)
    return if (any { it.id == normalized.id }) {
        map { if (it.id == normalized.id) normalized else it }
    } else {
        this + normalized
    }
}

@Serializable
private data class PersistedCustomAppPalette(
    val id: String,
    val name: String,
    val background: Long,
    val surface: Long,
    val primary: Long,
    val secondary: Long
)

@Serializable
private data class PersistedCustomAppPalettes(
    val palettes: List<PersistedCustomAppPalette> = emptyList(),
    val builtInOverrides: List<PersistedBuiltInOverride> = emptyList(),
    val hiddenBuiltIns: List<String> = emptyList()
)

@Serializable
private data class PersistedBuiltInOverride(
    val builtInId: String,
    val value: PersistedCustomAppPalette
)

private val customPaletteJson = Json { ignoreUnknownKeys = true }
private val CUSTOM_THEME_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,100}")
internal const val MAX_CUSTOM_THEME_NAME_LENGTH = 80

internal fun encodeThemeWorkspace(workspace: ThemeWorkspace): String =
    customPaletteJson.encodeToString(
        PersistedCustomAppPalettes(
            palettes = workspace.customPalettes.map(CustomAppPalette::toPersisted),
            builtInOverrides = workspace.builtInOverrides.map { (builtIn, value) ->
                PersistedBuiltInOverride(builtIn.persistedValue, value.toPersisted())
            },
            hiddenBuiltIns = workspace.hiddenBuiltIns.map(AppThemePalette::persistedValue)
        )
    )

internal fun decodeThemeWorkspace(value: String?): ThemeWorkspace {
    if (value.isNullOrBlank()) return ThemeWorkspace()
    return runCatching {
        val persisted = customPaletteJson.decodeFromString<PersistedCustomAppPalettes>(value)
        ThemeWorkspace(
            customPalettes = persisted.palettes.mapNotNull(PersistedCustomAppPalette::toDomain)
                .distinctBy(CustomAppPalette::id),
            builtInOverrides = persisted.builtInOverrides.mapNotNull { override ->
                val builtIn = AppThemePalette.entries.firstOrNull {
                    it.persistedValue == override.builtInId
                } ?: return@mapNotNull null
                override.value.toDomain()?.let { builtIn to it.copy(id = builtIn.persistedValue) }
            }.toMap(),
            hiddenBuiltIns = persisted.hiddenBuiltIns.mapNotNull { id ->
                AppThemePalette.entries.firstOrNull { it.persistedValue == id }
            }.toSet()
        )
    }.getOrDefault(ThemeWorkspace())
}

internal fun encodeCustomAppPalettes(values: List<CustomAppPalette>): String =
    encodeThemeWorkspace(ThemeWorkspace(customPalettes = values))

internal fun decodeCustomAppPalettes(value: String?): List<CustomAppPalette> =
    decodeThemeWorkspace(value).customPalettes

private fun CustomAppPalette.toPersisted() = PersistedCustomAppPalette(
    id = id,
    name = name,
    background = palette.background.persistedArgb(),
    surface = palette.surface.persistedArgb(),
    primary = palette.primary.persistedArgb(),
    secondary = palette.secondary.persistedArgb()
)

private fun PersistedCustomAppPalette.toDomain(): CustomAppPalette? {
    if (!id.matches(CUSTOM_THEME_ID_PATTERN)) return null
    val normalizedName = name.trim()
    if (normalizedName.isEmpty() || normalizedName.length > MAX_CUSTOM_THEME_NAME_LENGTH) return null
    val backgroundColor = Color(background)
    return CustomAppPalette(
        id = id,
        name = normalizedName,
        palette = AppPalette(
            background = backgroundColor,
            surface = Color(surface),
            primary = Color(primary),
            secondary = Color(secondary)
        )
    )
}

private fun Color.persistedArgb(): Long = toArgb().toLong() and 0xFFFF_FFFFL

/**
 * Small subset used by palette previews in Settings.
 */
internal data class AppPalettePreview(
    val background: Color,
    val surface: Color,
    val primary: Color,
    val secondary: Color
)

/**
 * Official RankOffline palettes.
 *
 * These are the only values that need to be edited when changing
 * the four principal colors of an official theme.
 */
internal fun AppThemePalette.palette(): AppPalette = when (this) {

    AppThemePalette.MIDNIGHT_NEON -> AppPalette(
        background = Color(0xFF000000),
        surface = Color(0xFF121522),
        primary = Color(0xFF19D5E5),
        secondary = Color(0xFFE134CE)
    )

    AppThemePalette.DEEP_OCEAN -> AppPalette(
        background = Color(0xFF051A2B),
        surface = Color(0xFF0A2E45),
        primary = Color(0xFF4FB4F7),
        secondary = Color(0xFF3656F8)
    )

    AppThemePalette.EMERALD_FOREST -> AppPalette(
        background = Color(0xFF0B2D1B),
        surface = Color(0xFF125231),
        primary = Color(0xFF44D64C),
        secondary = Color(0xFFCADB4B)
    )

    AppThemePalette.GOLDEN_SUNSET -> AppPalette(
        background = Color(0xFF42210A),
        surface = Color(0xFF79462F),
        primary = Color(0xFFFFCA5C),
        secondary = Color(0xFFFF61D2)
    )

    AppThemePalette.PINK_EXPLOSION -> AppPalette(
        background = Color(0xFF48052B),
        surface = Color(0xFF751150),
        primary = Color(0xFFFF2DBF),
        secondary = Color(0xFF9B53FF)
    )

    AppThemePalette.CHERRY_BLOSSOM -> AppPalette(
        background = Color(0xFFFCE5EC),
        surface = Color(0xFFFFCEE1),
        primary = Color(0xFFB92344),
        secondary = Color(0xFF551D30)
    )

    AppThemePalette.LAVENDER_DREAM -> AppPalette(
        background = Color(0xFFF2E3FD),
        surface = Color(0xFFDDD0FF),
        primary = Color(0xFF572CC7),
        secondary = Color(0xFF4763FF)
    )
}

/**
 * Preview shown in Settings.
 */
internal fun AppThemePalette.preview(): AppPalettePreview {
    val palette = palette()

    return AppPalettePreview(
        background = palette.background,
        surface = palette.surface,
        primary = palette.primary,
        secondary = palette.secondary
    )
}

/**
 * Material 3 ColorScheme for an official palette.
 */
internal fun AppThemePalette.colorScheme(): ColorScheme =
    palette().toColorScheme()

/**
 * Converts any RankOffline palette — official or user-created —
 * into a complete Material 3 color scheme.
 */
internal fun AppPalette.toColorScheme(): ColorScheme {

    val surfaceContent = bestContentColor(surface)
    val backgroundContent = bestContentColor(background)
    val surfaceVariant = lerp(surface, surfaceContent, 0.08f)
    val outline = lerp(surface, surfaceContent, 0.42f)
    val outlineVariant = lerp(surface, surfaceContent, 0.22f)
    val primaryContainer = lerp(primary, background, 0.68f)
    val secondaryContainer = lerp(secondary, background, 0.68f)

    val tertiaryContainer = lerp(
        secondary,
        background,
        0.68f
    )
    val error = if (bestContentColor(background) == Color.White) Color(0xFFFF675D)
    else Color(0xFFB3261E)

    // A single deterministic mapping is used for every palette. Foregrounds are derived from the
    // actual container color; there is no application-wide light/dark classification.
    return lightColorScheme(
        primary = primary,
        onPrimary = bestContentColor(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = bestContentColor(primaryContainer),
        secondary = secondary,
        onSecondary = bestContentColor(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = bestContentColor(secondaryContainer),
        tertiary = secondary,
        onTertiary = bestContentColor(secondary),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = bestContentColor(tertiaryContainer),
        background = background,
        onBackground = backgroundContent,
        surface = surface,
        onSurface = surfaceContent,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = bestContentColor(surfaceVariant),
        surfaceDim = lerp(surface, surfaceContent, 0.14f),
        surfaceBright = lerp(surface, surfaceContent, 0.04f),
        surfaceContainerLowest = background,
        surfaceContainerLow = lerp(surface, surfaceContent, 0.03f),
        surfaceContainer = lerp(surface, surfaceContent, 0.06f),
        surfaceContainerHigh = lerp(surface, surfaceContent, 0.10f),
        surfaceContainerHighest = lerp(surface, surfaceContent, 0.14f),
        outline = outline,
        outlineVariant = outlineVariant,
        error = error,
        onError = bestContentColor(error),
        errorContainer = lerp(error, background, 0.68f),
        onErrorContainer = bestContentColor(lerp(error, background, 0.68f)),
        inverseSurface = surfaceContent,
        inverseOnSurface = surface,
        inversePrimary = primary,
        scrim = Color.Black,
        surfaceTint = primary
    )
}

/**
 * Automatically chooses black or white depending on which provides
 * better contrast against the supplied color.
 *
 * Especially useful for future custom themes, where RankOffline cannot
 * know beforehand which colors the user will choose.
 */
internal fun bestContentColor(background: Color): Color {
    val white = Color.White
    val black = Color.Black

    val backgroundLuminance = background.luminance()

    val whiteContrast =
        (white.luminance() + 0.05f) /
            (backgroundLuminance + 0.05f)

    val blackContrast =
        (backgroundLuminance + 0.05f) /
            (black.luminance() + 0.05f)

    return if (whiteContrast >= blackContrast) {
        white
    } else {
        black
    }
}
