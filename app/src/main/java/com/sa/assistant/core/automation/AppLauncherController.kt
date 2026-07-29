package com.sa.assistant.core.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.sa.assistant.data.model.LaunchableApp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real installed-app listing and launching.
 *
 * [listLaunchableApps] finds apps the standard, no-special-permission
 * way: querying for activities that answer `ACTION_MAIN` /
 * `CATEGORY_LAUNCHER` — exactly what the device's own home-screen app
 * drawer does. This deliberately avoids `QUERY_ALL_PACKAGES`, which
 * Play Store restricts heavily; since this build already isn't for the
 * Play Store, that restriction doesn't matter, but the launcher-query
 * approach is also just the correct one for "apps a user can open."
 */
@Singleton
class AppLauncherController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val pm: PackageManager get() = context.packageManager

    suspend fun listLaunchableApps(): List<LaunchableApp> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map { resolveInfo ->
                LaunchableApp(
                    packageName = resolveInfo.activityInfo.packageName,
                    label = resolveInfo.loadLabel(pm).toString(),
                    icon = try {
                        resolveInfo.loadIcon(pm)
                    } catch (e: Exception) {
                        null
                    }
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Returns false if the package has no launchable activity (already uninstalled, etc.) rather than throwing. */
    fun launch(packageName: String): Boolean {
        val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return false
        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
