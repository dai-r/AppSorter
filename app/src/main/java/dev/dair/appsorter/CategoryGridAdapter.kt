package dev.dair.appsorter

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import dev.dair.appsorter.databinding.ItemCategoryTileBinding
import dev.dair.appsorter.model.CategorizedApp

class CategoryGridAdapter(
    private val packageManager: PackageManager,
    private val onCategoryClick: (String) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<CategoryGridAdapter.ViewHolder>() {

    data class Item(val category: String, val apps: List<CategorizedApp>)

    private val items = mutableListOf<Item>()

    fun submitItems(newItems: List<Item>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun currentOrder(): List<String> = items.map { it.category }

    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCategoryTileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context

        holder.binding.txtCategory.text = CategoryLabel.displayName(ctx, item.category)
        holder.binding.txtCount.text = item.apps.size.toString()

        // ミニアイコン 2x2 (最大4個)
        val miniGrid = holder.binding.miniGrid
        miniGrid.removeAllViews()
        val density = ctx.resources.displayMetrics.density
        val cellPx = (28 * density).toInt()
        val marginPx = (3 * density).toInt()
        val toShow = item.apps.take(4)
        toShow.forEach { app ->
            miniGrid.addView(miniCell(ctx, cellPx, marginPx).also {
                try {
                    it.setImageDrawable(packageManager.getApplicationIcon(app.packageName))
                } catch (_: PackageManager.NameNotFoundException) {
                    // blank
                }
            })
        }
        // 残りは空セルで2x2レイアウトを保つ
        repeat(4 - toShow.size) {
            miniGrid.addView(miniCell(ctx, cellPx, marginPx))
        }

        holder.binding.root.setOnClickListener {
            onCategoryClick(item.category)
        }
        holder.binding.root.setOnLongClickListener {
            onStartDrag(holder)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    private fun miniCell(
        ctx: android.content.Context,
        sizePx: Int,
        marginPx: Int,
    ): ImageView {
        val iv = ImageView(ctx)
        val lp = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = sizePx
            height = sizePx
            setMargins(marginPx, marginPx, marginPx, marginPx)
        }
        iv.layoutParams = lp
        return iv
    }

    class ViewHolder(val binding: ItemCategoryTileBinding) :
        RecyclerView.ViewHolder(binding.root)
}
