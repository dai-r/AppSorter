package dev.dair.appsorter

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.dair.appsorter.databinding.ItemCategoryPageBinding
import dev.dair.appsorter.model.CategorizedApp

class CategoryPagerAdapter(
    private val packageManager: PackageManager,
    private val onAppLongPress: (CategorizedApp) -> Unit,
) : RecyclerView.Adapter<CategoryPagerAdapter.PageViewHolder>() {

    data class Page(
        val category: String, // 内部カテゴリ名 or CategoryLabel.ALL
        val apps: List<CategorizedApp>,
    )

    private val pages = mutableListOf<Page>()

    fun submitPages(newPages: List<Page>) {
        pages.clear()
        pages.addAll(newPages)
        notifyDataSetChanged()
    }

    fun getPage(position: Int): Page? = pages.getOrNull(position)

    fun findPageIndex(category: String): Int =
        pages.indexOfFirst { it.category == category }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemCategoryPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PageViewHolder(binding, packageManager, onAppLongPress)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(pages[position])
    }

    override fun getItemCount(): Int = pages.size

    class PageViewHolder(
        private val binding: ItemCategoryPageBinding,
        packageManager: PackageManager,
        onAppLongPress: (CategorizedApp) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private val adapter = AppTileAdapter(packageManager, onAppLongPress)

        init {
            binding.recyclerApps.layoutManager = GridLayoutManager(itemView.context, 4)
            binding.recyclerApps.adapter = adapter
        }

        fun bind(page: Page) {
            adapter.submitList(page.apps)
            binding.txtEmpty.visibility =
                if (page.apps.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}
