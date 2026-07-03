package dev.dair.appsorter.widget

import android.content.Context

/**
 * widgetId → category / ピン留めアプリの永続化。
 * ウィジェットごとに「貼ってあるカテゴリ」と「先頭に固定するアプリ（ピン）」を覚える。
 */
object CategoryWidgetPrefs {
    private const val PREFS = "appsorter_widget_prefs"
    private const val PIN_DELIM = "\n"  // package名に改行は含まれないので区切りに使える

    private fun key(widgetId: Int) = "widget_${widgetId}_category"
    private fun pinsKey(widgetId: Int) = "widget_${widgetId}_pins"

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

    /** 先頭に固定表示するアプリ（選んだ順）。空なら完全に自動（頻度/名前）順。 */
    fun setPins(context: Context, widgetId: Int, packages: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(pinsKey(widgetId), packages.joinToString(PIN_DELIM))
            .apply()
    }

    fun getPins(context: Context, widgetId: Int): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(pinsKey(widgetId), null)
        return if (raw.isNullOrEmpty()) emptyList() else raw.split(PIN_DELIM)
    }

    fun clear(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key(widgetId))
            .remove(pinsKey(widgetId))
            .apply()
    }
}
