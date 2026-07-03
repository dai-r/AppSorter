package dev.dair.appsorter.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.dair.appsorter.CategoryLabel
import dev.dair.appsorter.CategoryOrder
import dev.dair.appsorter.R
import dev.dair.appsorter.data.AppSorterDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ウィジェット配置時に呼ばれる config activity。
 * ①カテゴリを選ばせる → ②そのカテゴリ内でピン留め（先頭固定）するアプリを選ばせる。
 * ピンを選ばなければ完全に自動（使用頻度順／権限なしは名前順）。
 */
class CategoryWidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ユーザーがキャンセルしたら widget が placed されない仕様
        setResult(Activity.RESULT_CANCELED)

        widgetId = intent.getIntExtra(
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
                showPinPicker(selected)
            }
            .setOnCancelListener { finish() }
            .show()
    }

    /** カテゴリ内のアプリを一覧し、ピン留めするものを選ばせる。 */
    private fun showPinPicker(category: String) {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                AppSorterDatabase.build(applicationContext).appCategoryDao()
                    .getAll().filter { it.category == category }  // app_name 昇順
            }
            if (apps.isEmpty()) {
                // 該当アプリなし＝ピン選択をスキップしてそのまま配置
                commit(category, emptyList())
                return@launch
            }

            val names = apps.map { it.appName }.toTypedArray()
            val existing = CategoryWidgetPrefs.getPins(this@CategoryWidgetConfigActivity, widgetId).toSet()
            val checked = BooleanArray(apps.size) { apps[it].packageName in existing }

            AlertDialog.Builder(this@CategoryWidgetConfigActivity)
                .setTitle(R.string.widget_pick_pins)
                .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    var pins = apps.filterIndexed { i, _ -> checked[i] }.map { it.packageName }
                    if (pins.size > MAX_PINS) {
                        pins = pins.take(MAX_PINS)
                        Toast.makeText(
                            this@CategoryWidgetConfigActivity,
                            R.string.widget_pin_max,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    commit(category, pins)
                }
                .setOnCancelListener { finish() }  // 戻る＝配置キャンセル
                .show()
        }
    }

    /** カテゴリ＋ピンを保存し、描画してウィジェットを確定する。 */
    private fun commit(category: String, pins: List<String>) {
        CategoryWidgetPrefs.setCategory(this, widgetId, category)
        CategoryWidgetPrefs.setPins(this, widgetId, pins)
        lifecycleScope.launch {
            val manager = AppWidgetManager.getInstance(this@CategoryWidgetConfigActivity)
            withContext(Dispatchers.IO) {
                CategoryWidgetProvider.renderWidget(
                    this@CategoryWidgetConfigActivity,
                    manager,
                    widgetId,
                )
            }
            // 使用頻度順にしたい人向けに、未許可なら一度だけ案内する
            if (!UsageRanker.hasAccess(this@CategoryWidgetConfigActivity)) {
                Toast.makeText(
                    this@CategoryWidgetConfigActivity,
                    R.string.widget_usage_hint,
                    Toast.LENGTH_LONG,
                ).show()
            }
            val result = Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }

    companion object {
        /** ピン留めの上限（最大グリッド 3×3 に合わせる）。 */
        private const val MAX_PINS = 9
    }
}
