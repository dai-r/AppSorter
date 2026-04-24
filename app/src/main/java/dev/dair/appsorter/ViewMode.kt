package dev.dair.appsorter

import android.content.Context

enum class ViewMode { CATEGORY, APPS }

object ViewModePrefs {
    private const val PREFS = "appsorter_prefs"
    private const val KEY = "view_mode"

    fun load(context: Context): ViewMode {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, ViewMode.CATEGORY.name) ?: ViewMode.CATEGORY.name
        return runCatching { ViewMode.valueOf(name) }.getOrDefault(ViewMode.CATEGORY)
    }

    fun save(context: Context, mode: ViewMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, mode.name)
            .apply()
    }
}
