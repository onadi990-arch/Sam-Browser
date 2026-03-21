package com.sam.browser

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class HistoryAdapter(
    private var items: List<JSONObject>,
    private val onItemClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle:  TextView    = v.findViewById(R.id.tvHistoryTitle)
        val tvUrl:    TextView    = v.findViewById(R.id.tvHistoryUrl)
        val btnDelete: ImageButton = v.findViewById(R.id.btnDeleteHistory)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val url   = item.optString("url")
        val title = item.optString("title")

        holder.tvTitle.text = if (title != url && title.isNotBlank()) title else ""
        holder.tvTitle.visibility = if (holder.tvTitle.text.isNotBlank()) View.VISIBLE else View.GONE
        holder.tvUrl.text = url

        holder.itemView.setOnClickListener { onItemClick(url) }
        holder.btnDelete.setOnClickListener { onDeleteClick(url) }
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<JSONObject>) {
        items = newItems
        notifyDataSetChanged()
    }
}
