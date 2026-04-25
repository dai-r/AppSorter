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
 * カテゴリショートカットウィジェット。
 * - 配置時に CategoryWidgetConfigActivity で category を選ばせる
 * - 描画時に DAO 経由で count と先頭 4 アプリのアイコンを取得
 * - タップで MainActivity を起動し EXTRA_CATEGORY で該当カテゴリへドリルダウン
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
            val all = dao.getAll()
            val matches = all.filter { it.category == category }
            val count = matches.size
            val iconPackages = matches.take(4).map { it.packageName }
            val pm = context.packageManager

            val views = RemoteViews(context.packageName, R.layout.widget_category)
            views.setTextViewText(
                R.id.widgetCategoryName,
                CategoryLabel.displayName(context, category)
            )
            views.setTextViewText(R.id.widgetCount, count.toString())

            val iconIds = intArrayOf(
                R.id.widgetIcon1, R.id.widgetIcon2, R.id.widgetIcon3, R.id.widgetIcon4
            )
            for (i in iconIds.indices) {
                val bitmap = iconPackages.getOrNull(i)?.let { pkg ->
                    try {
                        drawableToBitmap(pm.getApplicationIcon(pkg))
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                }
                if (bitmap != null) {
                    views.setImageViewBitmap(iconIds[i], bitmap)
                    views.setViewVisibility(iconIds[i], View.VISIBLE)
                } else {
                    views.setViewVisibility(iconIds[i], View.INVISIBLE)
                }
            }

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
