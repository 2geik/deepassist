package com.deepassist.util

import android.content.Context
import android.content.Intent

/** Finds an installed app by its spoken name (Turkish letters ignored) and opens it. */
object AppLauncher {

    data class App(val packageName: String, val label: String)

    fun find(context: Context, name: String): App? {
        val pm = context.packageManager
        val launchers = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val wanted = DeviceUtils.toAsciiTurkce(name.trim())
        if (wanted.isBlank()) return null
        val apps = launchers.map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        // Exact name first, then a name that contains what was said
        return apps.firstOrNull { DeviceUtils.toAsciiTurkce(it.label) == wanted }
            ?: apps.firstOrNull { DeviceUtils.toAsciiTurkce(it.label).contains(wanted) }
    }

    fun launch(context: Context, app: App): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
