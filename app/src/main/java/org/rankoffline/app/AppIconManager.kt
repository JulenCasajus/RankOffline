package org.rankoffline.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

enum class AppIcon(
    @StringRes val displayNameResource: Int,
    val persistedValue: String,
    val aliasClassName: String,
    @DrawableRes val previewResource: Int
) {
    DARK(R.string.icon_default, "DARK", "MainActivityDark", R.drawable.rankoffline_dark_source),
    BLUE(R.string.icon_blue, "BLUE", "MainActivityBlue", R.drawable.rankoffline_blue_source),
    RED(R.string.icon_red, "RED", "MainActivityRed", R.drawable.rankoffline_red_source),
    GREEN(R.string.icon_green, "GREEN", "MainActivityGreen", R.drawable.rankoffline_green_source),
    YELLOW(R.string.icon_yellow, "YELLOW", "MainActivityYellow", R.drawable.rankoffline_yellow_source),
    STAR(R.string.icon_star, "STAR", "MainActivityStar", R.drawable.rankoffline_star_source),
    STAR_NO_BACKGROUND(
        R.string.icon_star_no_background,
        "STAR_NO_BACKGROUND",
        "MainActivityStarNoBackground",
        R.drawable.rankoffline_star_no_background_source
    ),
    STAR_PASTEL(
        R.string.icon_star_pastel,
        "STAR_PASTEL",
        "MainActivityStarPastel",
        R.drawable.rankoffline_star_pastel_source
    ),
    STAR_WHITE(
        R.string.icon_star_white,
        "STAR_WHITE",
        "MainActivityStarWhite",
        R.drawable.rankoffline_star_white_source
    ),
    DARK_GLOW(
        R.string.icon_dark_glow,
        "DARK_GLOW",
        "MainActivityDarkGlow",
        R.drawable.rankoffline_dark_glow_source
    );

    fun componentClassName(packageName: String): String = "$packageName.$aliasClassName"

    companion object {
        fun fromPersistedValue(value: String?): AppIcon =
            entries.firstOrNull { it.persistedValue == value } ?: DARK
    }
}

internal interface AppIconAliasController {
    fun activate(icon: AppIcon, previousIcon: AppIcon)
}

internal class PackageManagerAppIconAliasController(
    private val context: Context
) : AppIconAliasController {
    private val packageManager = context.packageManager

    @Suppress("DEPRECATION")
    override fun activate(icon: AppIcon, previousIcon: AppIcon) {
        val components = AppIcon.entries.associateWith { candidate ->
            ComponentName(context.packageName, candidate.componentClassName(context.packageName))
        }

        // Validate the complete mapping before changing any state. A typo must never hide the app.
        components.values.forEach { component ->
            packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        }

        packageManager.setComponentEnabledSetting(
            components.getValue(icon),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )

        if (previousIcon != icon) {
            packageManager.setComponentEnabledSetting(
                components.getValue(previousIcon),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}

internal suspend fun persistAndActivateAppIcon(
    icon: AppIcon,
    previousIcon: AppIcon,
    persistSelection: suspend (AppIcon) -> Unit,
    activateAlias: (AppIcon, AppIcon) -> Unit
) {
    persistSelection(icon)
    activateAlias(icon, previousIcon)
}
