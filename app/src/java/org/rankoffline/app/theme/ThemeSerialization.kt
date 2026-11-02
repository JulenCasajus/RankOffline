package org.rankoffline.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
internal val CUSTOM_THEME_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,100}")
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

