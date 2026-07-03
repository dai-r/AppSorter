package dev.dair.appsorter.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import dev.dair.appsorter.CategoryLabel
import dev.dair.appsorter.MainActivity
import dev.dair.appsorter.R
import dev.dair.appsorter.data.AppSorterDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * カテゴリショートカット兼ミニランチャーウィジェット。
 * - 配置時に CategoryWidgetConfigActivity で category とピン留めアプリを選ばせる
 * - 描画時に DAO 経由で count と表示アプリ（ピン優先＋使用頻度で補充）を取得
 * - サイズに応じて 4 / 6 / 9 個のアイコンを表示（リサイズで自動切替）
 * - アイコンをタップ＝そのアプリを直接起動
 * - カテゴリ名・余白をタップ＝MainActivity を該当カテゴリへドリルダウン
 */
class CategoryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                for (id in appWidgetIds) {
                    renderWidget(context, appWidgetManager, id)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** リサイズ時に呼ばれる。段組み（4/6/9）を追従させるため再描画する。 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                renderWidget(context, appWidgetManager, appWidgetId)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            CategoryWidgetPrefs.clear(context, id)
        }
    }

    companion object {
        const val EXTRA_CATEGORY = "dev.dair.appsorter.extra.CATEGORY"

        // 破棄されない軽量な scope。onUpdate / updateAll から goAsync 併用で使う。
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 単一ウィジェットの再描画。DB アクセスするので IO スレッドから呼ぶこと。 */
        suspend fun renderWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
        ) {
            val category = CategoryWidgetPrefs.getCategory(context, widgetId) ?: return
            val dao = AppSorterDatabase.build(context).appCategoryDao()
            val matches = dao.getAll().filter { it.category == category }  // 既に app_name 昇順
            val count = matches.size

            // 表示枠数をウィジェットサイズから決定（4/6/9）
            val slots = slotCountFor(manager, widgetId)
            val iconIds = iconIdsFor(slots)

            // 並び順: ピン優先 → 残りを使用頻度順（未許可なら名前順）で補充
            val validPins = CategoryWidgetPrefs.getPins(context, widgetId)
                .filter { pkg -> matches.any { it.packageName == pkg } }
            val scores = UsageRanker.scores(context)  // 未許可なら空Map
            val autoOrder = if (scores.isNotEmpty()) {
                matches.map { it.packageName }.sortedByDescending { scores[it] ?: 0L }
            } else {
                matches.map { it.packageName }  // 名前順（getAll の並び）
            }
            val iconPackages = (validPins + autoOrder.filterNot { it in validPins })
                .distinct()
                .take(slots)

            val pm = context.packageManager
            val views = RemoteViews(context.packageName, layoutFor(slots))
            views.setTextViewText(
                R.id.widgetCategoryName,
                CategoryLabel.displayName(context, category)
            )
            views.setTextViewText(R.id.widgetCount, count.toString())

            for (i in iconIds.indices) {
                val pkg = iconPackages.getOrNull(i)
                val bitmap = pkg?.let {
                    try {
                        drawableToBitmap(pm.getApplicationIcon(it))
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                }
                if (pkg != null && bitmap != null) {
                    views.setImageViewBitmap(iconIds[i], bitmap)
                    views.setViewVisibility(iconIds[i], View.VISIBLE)
                    // アイコン個別タップ＝そのアプリを直接起動
                    val launch = pm.getLaunchIntentForPackage(pkg)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        // widget×icon で衝突しない requestCode
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        val pi = PendingIntent.getActivity(context, widgetId * 16 + i, launch, flags)
                        views.setOnClickPendingIntent(iconIds[i], pi)
                    }
                } else {
                    views.setViewVisibility(iconIds[i], View.INVISIBLE)
                }
            }

            // ルート全体＝カテゴリ画面へジャンプ（アイコン以外＝カテゴリ名・余白はこちらにフォールバック）
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_CATEGORY, category)
                // 同じカテゴリで複数ウィジェットあっても個別に PendingIntent を区別させるため
                data = android.net.Uri.parse("appsorter://widget/$widgetId/$category")
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pending = PendingIntent.getActivity(context, widgetId, intent, flags)
            views.setOnClickPendingIntent(R.id.widgetRoot, pending)

            manager.updateAppWidget(widgetId, views)
        }

        /** 全ウィジェットを再描画。スキャン完了時や手動カテゴリ変更時に呼ぶ。 */
        fun updateAll(context: Context) {
            scope.launch {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, CategoryWidgetProvider::class.java)
                )
                for (id in ids) renderWidget(context, manager, id)
            }
        }

        /** ウィジェットの現在サイズから表示枠数（4/6/9）を求める。 */
        private fun slotCountFor(manager: AppWidgetManager, widgetId: Int): Int {
            val opts = manager.getAppWidgetOptions(widgetId)
            val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            // dp → セル数の目安: (dp + 30) / 70。未確定(0)時は最小の 2 とみなす
            val cols = ((minW + 30) / 70).coerceAtLeast(2)
            val rows = ((minH + 30) / 70).coerceAtLeast(2)
            return when {
                cols >= 3 && rows >= 3 -> 9
                cols >= 3 && rows >= 2 -> 6
                else -> 4
            }
        }

        private fun layoutFor(slots: Int): Int = when (slots) {
            9 -> R.layout.widget_category_9
            6 -> R.layout.widget_category_6
            else -> R.layout.widget_category_4
        }

        private fun iconIdsFor(slots: Int): IntArray = when (slots) {
            9 -> intArrayOf(
                R.id.widgetIcon1, R.id.widgetIcon2, R.id.widgetIcon3,
                R.id.widgetIcon4, R.id.widgetIcon5, R.id.widgetIcon6,
                R.id.widgetIcon7, R.id.widgetIcon8, R.id.widgetIcon9,
            )
            6 -> intArrayOf(
                R.id.widgetIcon1, R.id.widgetIcon2, R.id.widgetIcon3,
                R.id.widgetIcon4, R.id.widgetIcon5, R.id.widgetIcon6,
            )
            else -> intArrayOf(
                R.id.widgetIcon1, R.id.widgetIcon2, R.id.widgetIcon3, R.id.widgetIcon4,
            )
        }

        private fun drawableToBitmap(drawable: Drawable): Bitmap {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                return drawable.bitmap
            }
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }
    }
}
