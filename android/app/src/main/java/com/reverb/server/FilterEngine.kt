package com.reverb.server

import android.content.Context
import com.reverb.model.FilterConfig

object FilterEngine {
    private const val PREFS_NAME = "reverb_filters"
    private const val KEY_MODE = "mode"
    private const val KEY_PACKAGES = "packages"

    fun isAllowed(context: Context, packageName: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, "blacklist") ?: "blacklist"
        val packages = prefs.getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()

        return when (mode) {
            "whitelist" -> packageName in packages
            else -> packageName !in packages  // blacklist: 목록에 없으면 허용
        }
    }

    fun getConfig(context: Context): FilterConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, "blacklist") ?: "blacklist"
        val packages = prefs.getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()
        return FilterConfig(mode = mode, packages = packages.toList().sorted())
    }

    fun setConfig(context: Context, config: FilterConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, config.mode)
            .putStringSet(KEY_PACKAGES, config.packages.toSet())
            .apply()
    }

    fun addPackage(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_PACKAGES, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        current.add(packageName)
        prefs.edit().putStringSet(KEY_PACKAGES, current).apply()
    }

    fun removePackage(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_PACKAGES, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY_PACKAGES, current).apply()
    }

    fun setMode(context: Context, mode: String) {
        require(mode == "whitelist" || mode == "blacklist")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, mode)
            .apply()
    }
}
