package org.rankoffline.app

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AppIconManagerIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager = context.packageManager
    private val namespace = MainActivity::class.java.name.substringBeforeLast('.')

    @Test
    fun activationUsesMergedManifestAliasesAndLeavesExactlyOneLauncherEnabled() {
        val controller = PackageManagerAppIconAliasController(context)
        val original = controller.activeIconOrNull() ?: AppIcon.DARK
        val replacement = AppIcon.selectableIcons.first { it != original }

        try {
            controller.activate(replacement, original)
            assertEquals(replacement, controller.activeIconOrNull())
            assertEquals(1, AppIcon.entries.count(::isEffectivelyEnabled))
            assertNotNull(
                packageManager.getActivityInfo(
                    component(replacement),
                    PackageManager.MATCH_DISABLED_COMPONENTS
                )
            )
        } finally {
            controller.activate(original, replacement)
        }

        assertEquals(original, controller.activeIconOrNull())
        assertEquals(1, AppIcon.entries.count(::isEffectivelyEnabled))
    }

    @Suppress("DEPRECATION")
    private fun isEffectivelyEnabled(icon: AppIcon): Boolean {
        val component = component(icon)
        val info = packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        return when (packageManager.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> info.enabled
        }
    }

    private fun component(icon: AppIcon): ComponentName =
        ComponentName(context.packageName, icon.componentClassName(namespace))
}
