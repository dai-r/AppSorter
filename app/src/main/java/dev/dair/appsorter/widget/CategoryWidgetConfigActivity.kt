package dev.dair.appsorter.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.dair.appsorter.CategoryLabel
import dev.dair.appsorter.CategoryOrder
import dev.dair.appsorter.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ウィジェット配置時に呼ばれる config activity。カテゴリを選ばせて pref に保存する。
 */
class CategoryWidgetConfigActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ユーザーがキャンセルしたら widget が placed されない仕様
        setResult(Activity.RESULT_CANCELED)

        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val categories = CategoryOrder.load(this)
        val labels = categories.map { CategoryLabel.displayName(this, it) }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.widget_pick_category)
            .setItems(labels) { _, which ->
                val selected = categories[which]
                CategoryWidgetPrefs.setCategory(this, widgetId, selected)
                lifecycleScope.launch {
                    val manager = AppWidgetManager.getInstance(this@CategoryWidgetConfigActivity)
                    withContext(Dispatchers.IO) {
                        CategoryWidgetProvider.renderWidget(
                            this@CategoryWidgetConfigActivity,
                            manager,
                            widgetId,
                        )
                    }
                    val result = Intent().apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    }
                    setResult(Activity.RESULT_OK, result)
                    finish()
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }
}
