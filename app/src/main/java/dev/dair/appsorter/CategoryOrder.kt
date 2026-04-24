package dev.dair.appsorter

import android.content.Context

/**
 * カテゴリ表示順をユーザー設定として保存・復元する。
 * デフォルトは DEFAULT_ORDER、ユーザーが並べ替えたらそれを優先。
 */
object CategoryOrder {
    private const val PREFS = "appsorter_prefs"
    private const val KEY = "category_order"
    private const val SEP = "," // 内部カテゴリ名はASCII英字のみなのでカンマで衝突しない

    // 内部カテゴリ名。表示順の基準になるデフォルト。
    private val DEFAULT_ORDER = listOf(
        "Communication", "SNS", "Mail",
        "Video", "Music", "Photo",
        "Game",
        "Shopping", "Payment", "Points", "Banking", "Crypto",
        "Work", "News",
        "Navigation", "Travel", "Mobility",
        "Food", "Health", "Education",
        "Utility", "System",
        "Other",
    )

    fun defaultOrder(): List<String> = DEFAULT_ORDER

    fun load(context: Context): List<String> {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            ?: return DEFAULT_ORDER

        val defaults = DEFAULT_ORDER.toSet()
        // 既知カテゴリだけ採用し、未保存のカテゴリは末尾に追加
        val filtered = stored.filter { it in defaults }.distinct()
        val missing = DEFAULT_ORDER.filter { it !in filtered }
        return filtered + missing
    }

    fun save(context: Context, order: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, order.joinToString(SEP))
            .apply()
    }
}
