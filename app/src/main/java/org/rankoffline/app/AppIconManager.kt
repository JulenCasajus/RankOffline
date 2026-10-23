package org.rankoffline.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

enum class AppIcon(
    @StringRes val displayNameResource: Int,
    val persistedValue: String,
    val aliasClassName: String,
    @DrawableRes val previewResource: Int
) {
    DARK(R.string.icon_default_blue, "DARK", "MainActivityDefaultBlack", R.drawable.rankoffline_default_blue_source),
    DEFAULT_GLOW_BLUE(R.string.icon_default_glow_blue, "DEFAULT_GLOW_BLUE", "MainActivityDefaultGlowBlue", R.drawable.rankoffline_default_glow_blue_source),
    HIMMEL_BLUE(R.string.icon_himmel_blue, "HIMMEL_BLUE", "MainActivityHimmelBlue", R.drawable.rankoffline_himmel_blue_source),
    MAOMAO_GREEN(R.string.icon_maomao_green, "MAOMAO_GREEN", "MainActivityMaomaoGreen", R.drawable.rankoffline_maomao_green_source),
    MAKISE_RED(R.string.icon_makise_red, "MAKISE_RED", "MainActivityMakiseRed", R.drawable.rankoffline_makise_red_source),
    ZENITSU_YELLOW(R.string.icon_zenitsu_yellow, "ZENITSU_YELLOW", "MainActivityZenitsuYellow", R.drawable.rankoffline_zenitsu_yellow_source),
    GUTS_BLACK(R.string.icon_guts_black, "GUTS_BLACK", "MainActivityGutsBlack", R.drawable.rankoffline_guts_black_source),
    KILLUA_WHITE(R.string.icon_killua_white, "KILLUA_WHITE", "MainActivityKilluaWhite", R.drawable.rankoffline_killua_white_source);

    /** Alias class lives in the manifest namespace, which differs from the debug applicationId. */
    fun componentClassName(namespace: String): String = "$namespace.$aliasClassName"

    companion object {
        /** Ordered options shown in the two-column icon chooser. Hidden legacy icons remain valid. */
        val selectableIcons: List<AppIcon> = listOf(
            DARK,
            DEFAULT_GLOW_BLUE,
            HIMMEL_BLUE,
            MAOMAO_GREEN,
            MAKISE_RED,
            ZENITSU_YELLOW,
            GUTS_BLACK,
            KILLUA_WHITE







        )

        fun fromPersistedValue(value: String?): AppIcon =
            entries.firstOrNull { it.persistedValue == value } ?: DARK
    }
}

internal interface AppIconAliasController {
    fun activate(icon: AppIcon, previousIcon: AppIcon)
    fun activeIconOrNull(): AppIcon? = null
}

internal class PackageManagerAppIconAliasController(
    private val context: Context
) : AppIconAliasController {
    private val packageManager = context.packageManager
    private val manifestNamespace = MainActivity::class.java.name.substringBeforeLast('.')

    private fun component(icon: AppIcon) =
        ComponentName(context.packageName, icon.componentClassName(manifestNamespace))

    @Suppress("DEPRECATION")
    override fun activate(icon: AppIcon, previousIcon: AppIcon) {
        val components = AppIcon.entries.associateWith(::component)
        val target = components.getValue(icon)

        // Validate the selected alias before disabling anything or saving a preference.
        packageManager.getActivityInfo(target, PackageManager.MATCH_DISABLED_COMPONENTS)
        packageManager.setComponentEnabledSetting(
            target,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        check(packageManager.getComponentEnabledSetting(target) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            "Launcher alias failed to enable: $target"
        }

        // Reconcile all registered aliases: preferences may previously have diverged
        // from PackageManager state. Missing legacy aliases are optional.
        components.forEach { (candidate, component) ->
            if (candidate != icon) {
                try {
                    packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
                    packageManager.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (missing: PackageManager.NameNotFoundException) {
                    Log.w("RankOfflineIcon", "Missing optional launcher alias: $component", missing)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun activeIconOrNull(): AppIcon? = AppIcon.entries.firstOrNull { icon ->
        val component = component(icon)
        runCatching {
            val info = packageManager.getActivityInfo(
                component,
                PackageManager.MATCH_DISABLED_COMPONENTS
            )
            when (packageManager.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
                else -> info.enabled
            }
        }.getOrDefault(false)
    }
}

internal suspend fun persistAndActivateAppIcon(
    icon: AppIcon,
    previousIcon: AppIcon,
    persistSelection: suspend (AppIcon) -> Unit,
    activateAlias: (AppIcon, AppIcon) -> Unit
) {
    activateAlias(icon, previousIcon)
    persistSelection(icon)
}
