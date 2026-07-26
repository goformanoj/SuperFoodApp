package com.jarvis.os.control

import android.content.Context
import android.content.Intent

/**
 * Resolves a spoken app name (e.g. "WhatsApp", "instagram") to an installed
 * launchable app and starts it. Needs QUERY_ALL_PACKAGES to see other apps'
 * launcher entries on Android 11+. Returns the launched package name, or null
 * if nothing matched or it couldn't be launched.
 */
object AppLauncher {

    fun launch(context: Context, name: String): String? {
        val pm = context.packageManager
        val target = resolvePackage(context, name) ?: return null
        val intent = pm.getLaunchIntentForPackage(target) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            target
        } catch (e: Exception) {
            null
        }
    }

    /** Best matching launchable package for [name], or null. */
    fun resolvePackage(context: Context, name: String): String? {
        val pm = context.packageManager
        val query = name.trim().lowercase()
        if (query.isEmpty()) return null

        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)

        var contains: String? = null
        for (info in apps) {
            val label = info.loadLabel(pm).toString().lowercase()
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg == context.packageName) continue // never target ourselves
            if (label == query) return pkg // exact label wins
            if (contains == null && (label.contains(query) || query.contains(label))) {
                contains = pkg
            }
        }
        return contains
    }
}
