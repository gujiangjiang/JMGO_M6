package com.gujiangjiang.jmgocontrol.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gujiangjiang.jmgocontrol.databinding.ItemEntryBinding

data class Entry(
    val id: String,
    val iconRes: Int,
    val title: String,
    val subtitle: String?
)

class EntryAdapter(
    private val items: List<Entry>,
    private val onClick: (Entry) -> Unit
) : RecyclerView.Adapter<EntryAdapter.VH>() {

    class VH(val b: ItemEntryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.b.icon.setImageResource(e.iconRes)
        holder.b.title.text = e.title
        if (e.subtitle.isNullOrEmpty()) {
            holder.b.subtitle.visibility = View.GONE
        } else {
            holder.b.subtitle.visibility = View.VISIBLE
            holder.b.subtitle.text = e.subtitle
        }
        holder.b.root.setOnClickListener { onClick(e) }
    }
}
