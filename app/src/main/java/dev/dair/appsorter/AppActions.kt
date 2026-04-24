package dev.dair.appsorter

import android.content.Context
import androidx.appcompat.app.AlertDialog
import dev.dair.appsorter.model.CategorizedApp

/**
 * アプリタイルの長押し時に表示するアクションシート。
 */
object AppActions {

    fun showActionSheet(
        context: Context,
        app: CategorizedApp,
        onChangeCategory: (String) -> Unit,
        onOpenDetails: () -> Unit,
    ) {
        val options = arrayOf(
            context.getString(R.string.action_change_category),
            context.getString(R.string.action_open_details),
        )
        AlertDialog.Builder(context)
            .setTitle(app.appName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCategoryPicker(context, app.category, onChangeCategory)
                    1 -> onOpenDetails()
                }
            }
            .show()
    }

    private fun showCategoryPicker(
        context: Context,
        currentCategory: String,
        onSelected: (String) -> Unit,
    ) {
        val categories = CategoryOrder.defaultOrder()
        val labels = categories
            .map { CategoryLabel.displayName(context, it) }
            .toTypedArray()
        val initialIdx = categories.indexOf(currentCategory).coerceAtLeast(0)
        var choice = initialIdx

        AlertDialog.Builder(context)
            .setTitle(R.string.action_change_category)
            .setSingleChoiceItems(labels, initialIdx) { _, which -> choice = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newCategory = categories[choice]
                if (newCategory != currentCategory) {
                    onSelected(newCategory)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
