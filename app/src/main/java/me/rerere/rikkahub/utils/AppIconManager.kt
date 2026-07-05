package me.rerere.rikkahub.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Phase 17 — runtime app icon (and, on Android 12+, splash icon) switching.
 *
 * The MAIN/LAUNCHER entry point lives on one activity-alias per icon variant (see
 * AndroidManifest.xml); switching enables the chosen alias BEFORE disabling the others so
 * there is never a moment with zero launcher entries. DONT_KILL_APP keeps the app alive
 * through the switch; most launchers refresh the icon within a few seconds.
 *
 * Class names resolve against the manifest namespace (me.rerere.rikkahub), NOT the
 * applicationId (which carries flavor suffixes like .debug) — hence the hardcoded
 * ALIAS_PACKAGE below.
 */
enum class AppIconVariant(val aliasClassName: String) {
    DEFAULT("me.rerere.rikkahub.LauncherDefault"),
    OCEAN("me.rerere.rikkahub.LauncherOcean"),
    MIDNIGHT("me.rerere.rikkahub.LauncherMidnight"),
    SUNSET("me.rerere.rikkahub.LauncherSunset"),
}

object AppIconManager {

    fun currentVariant(context: Context): AppIconVariant {
        val pm = context.packageManager
        for (variant in AppIconVariant.entries) {
            val state = pm.getComponentEnabledSetting(componentOf(context, variant))
            when (state) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> return variant
                // DEFAULT means "as declared in the manifest": only LauncherDefault is
                // manifest-enabled, so DEFAULT counts as active for it alone.
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ->
                    if (variant == AppIconVariant.DEFAULT) return variant

                else -> Unit
            }
        }
        return AppIconVariant.DEFAULT
    }

    fun setVariant(context: Context, variant: AppIconVariant) {
        val pm = context.packageManager
        // Enable the new alias first so the launcher never sees zero entries.
        pm.setComponentEnabledSetting(
            componentOf(context, variant),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        AppIconVariant.entries.filter { it != variant }.forEach { other ->
            pm.setComponentEnabledSetting(
                componentOf(context, other),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun componentOf(context: Context, variant: AppIconVariant) =
        ComponentName(context.packageName, variant.aliasClassName)
}
