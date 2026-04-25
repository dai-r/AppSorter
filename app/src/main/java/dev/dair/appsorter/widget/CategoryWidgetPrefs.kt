package dev.dair.appsorter.widget

import android.content.Context

/**
 * widgetId → category の永続化。ウィジェットごとに貼ってあるカテゴリを覚える。
 */
object CategoryWidgetPrefs {
    private const val PREFS = "appsorter_widget_prefs"
    private fun key(widgetId: Int) = "widget_${widgetId}_category"

    fun setCategory(context: Context, widgetId: Int, category: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(widgetId), category)
            .apply()
    }

    fun getCategory(context: Context, widgetId: Int): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(widgetId), null)
    }

    fun clear(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key(widgetId))
            .apply()
    }
}
