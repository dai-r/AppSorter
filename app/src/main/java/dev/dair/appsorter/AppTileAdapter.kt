package dev.dair.appsorter

import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import dev.dair.appsorter.databinding.ItemAppTileBinding
import dev.dair.appsorter.model.CategorizedApp

class AppTileAdapter(
    private val packageManager: PackageManager,
    private val onLongPress: (CategorizedApp) -> Unit,
) : RecyclerView.Adapter<AppTileAdapter.ViewHolder>() {
    private val items = mutableListOf<CategorizedApp>()

    fun submitList(newItems: List<CategorizedApp>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppTileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.binding.txtAppName.text = item.appName

        val icon = try {
            packageManager.getApplicationIcon(item.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        holder.binding.imgIcon.setImageDrawable(icon)

        holder.binding.root.setOnClickListener {
            val launch = packageManager.getLaunchIntentForPackage(item.packageName)
            if (launch == null) {
                Toast.makeText(ctx, R.string.toast_cannot_launch, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                ctx.startActivity(launch)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(ctx, R.string.toast_cannot_launch, Toast.LENGTH_SHORT).show()
            }
        }
        holder.binding.root.setOnLongClickListener {
            onLongPress(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(val binding: ItemAppTileBinding) : RecyclerView.ViewHolder(binding.root)
}
