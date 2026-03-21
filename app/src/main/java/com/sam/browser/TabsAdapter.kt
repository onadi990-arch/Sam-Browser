package com.sam.browser

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TabsAdapter(
    private val tabs: List<TabData>,
    private val currentIndex: Int,
    private val onTabClick: (Int) -> Unit,
    private val onTabClose: (Int) -> Unit
) : RecyclerView.Adapter<TabsAdapter.TabViewHolder>() {

    inner class TabViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView     = view.findViewById(R.id.tvTabTitle)
        val tvUrl: TextView       = view.findViewById(R.id.tvTabUrl)
        val btnClose: ImageButton = view.findViewById(R.id.btnCloseTab)
        val activeIndicator: View = view.findViewById(R.id.activeIndicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
        return TabViewHolder(v)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val tab = tabs[position]
        holder.tvTitle.text = tab.title.ifEmpty { "New Tab" }
        holder.tvUrl.text = if (tab.isFileTab) tab.title else tab.url.ifEmpty { "about:blank" }
        holder.activeIndicator.visibility = if (position == currentIndex) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { onTabClick(position) }
        holder.btnClose.setOnClickListener { onTabClose(position) }
    }

    override fun getItemCount() = tabs.size
}
