package org.rankoffline.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** Launcher aliases are compiled into AndroidManifest.xml, never supplied by a catalog pack. */
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

    /** Manifest class namespace is not necessarily the debug applicationId. */
    fun componentClassName(namespace: String): String = "$namespace.$aliasClassName"

    companion object {
        /** Edit this list to hide a choice without breaking previously saved selections. */
        val selectableIcons: List<AppIcon> = listOf(
            DARK, DEFAULT_GLOW_BLUE, HIMMEL_BLUE, MAOMAO_GREEN,
            MAKISE_RED, ZENITSU_YELLOW, GUTS_BLACK, KILLUA_WHITE
        )

        fun fromPersistedValue(value: String?): AppIcon =
            entries.firstOrNull { it.persistedValue == value } ?: DARK
    }
}

internal interface AppIconAliasController {
    fun activate(icon: AppIcon, previousIcon: AppIcon)
    fun activeIconOrNull(): AppIcon? = null
}

/**
 * Changes only launcher activity-alias components. On API 33+ changes are atomic; on older
 * Android versions the previous component states are restored if an intermediate step fails.
 */
internal class PackageManagerAppIconAliasController(context: Context) : AppIconAliasController {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val manifestNamespace = MainActivity::class.java.name.substringBeforeLast('.')

    private fun component(icon: AppIcon): ComponentName =
        ComponentName(appContext.packageName, icon.componentClassName(manifestNamespace))

    @Synchronized
    override fun activate(icon: AppIcon, previousIcon: AppIcon) {
        // previousIcon is retained for the existing SettingsRepository contract; the actual
        // component states (rather than a potentially stale preference) are our rollback source.
        val known = availableAliases(required = icon)
        if (known.count { it.enabled } == 1 && known.first { it.enabled }.icon == icon) return

        val target = known.first { it.icon == icon }
        val changes = listOf(target.component to PackageManager.COMPONENT_ENABLED_STATE_ENABLED) +
            known.filterNot { it.icon == icon }.map {
                it.component to PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        try {
            applyStates(changes)
            check(activeIconOrNull() == icon) {
                "Unable to activate launcher alias ${icon.aliasClassName}"
            }
        } catch (failure: Exception) {
            restoreStates(known, failure)
            throw failure
        }
    }

    /** Returns null when no launcher alias is enabled OR several aliases are enabled. */
    @Synchronized
    override fun activeIconOrNull(): AppIcon? =
        availableAliases().filter { it.enabled }.singleOrNull()?.icon

    private fun availableAliases(required: AppIcon? = null): List<AliasState> =
        AppIcon.entries.mapNotNull { icon ->
            val component = component(icon)
            val info = try {
                activityInfo(component)
            } catch (missing: PackageManager.NameNotFoundException) {
                if (icon == required) {
                    throw IllegalStateException("Selected icon alias is missing from AndroidManifest: $component", missing)
                }
                return@mapNotNull null // An old alias may legitimately be absent in this build.
            }
            val state = packageManager.getComponentEnabledSetting(component)
            val enabled = when (state) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
                else -> info.enabled
            }
            AliasState(icon, component, state, enabled)
        }


    private fun restoreStates(aliases: List<AliasState>, originalFailure: Exception) {
        // Restore only components that changed. In particular, do not retry the very operation
        // that failed (e.g. disabling an older alias) before undoing an already-enabled target.
        val changes = aliases.sortedByDescending { it.enabled }.mapNotNull { alias ->
            (alias.component to alias.state).takeIf {
                packageManager.getComponentEnabledSetting(alias.component) != alias.state
            }
        }
        if (changes.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                applyStates(changes)
            } catch (rollbackFailure: Exception) {
                originalFailure.addSuppressed(rollbackFailure)
                Log.e(TAG, "Launcher alias rollback failed", rollbackFailure)
            }
        } else {
            // One failed restoration must not prevent the remaining components being restored.
            changes.forEach { (component, state) ->
                try {
                    packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                } catch (rollbackFailure: Exception) {
                    originalFailure.addSuppressed(rollbackFailure)
                    Log.e(TAG, "Unable to restore launcher alias $component", rollbackFailure)
                }
            }
        }
    }

    private fun activityInfo(component: ComponentName): ActivityInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getActivityInfo(
                component,
                PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        }

    private fun applyStates(changes: List<Pair<ComponentName, Int>>) {
        if (changes.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(changes.map { (component, state) ->
                PackageManager.ComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            })
        } else {
            changes.forEach { (component, state) ->
                packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
    }

    private data class AliasState(
        val icon: AppIcon,
        val component: ComponentName,
        val state: Int,
        val enabled: Boolean
    )

    private companion object { const val TAG = "RankOfflineIcon" }
}

/** Never leave a new launcher icon selected in PackageManager if saving its preference fails. */
internal suspend fun persistAndActivateAppIcon(
    icon: AppIcon,
    previousIcon: AppIcon,
    persistSelection: suspend (AppIcon) -> Unit,
    activateAlias: (AppIcon, AppIcon) -> Unit
) {
    activateAlias(icon, previousIcon)
    try {
        persistSelection(icon)
    } catch (failure: Exception) {
        if (icon != previousIcon) {
            try {
                activateAlias(previousIcon, icon)
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
        }
        throw failure
    }
}
