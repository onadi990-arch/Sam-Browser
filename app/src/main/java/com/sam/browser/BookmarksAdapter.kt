package com.sam.browser

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class BookmarksAdapter(
    private val bookmarks: List<JSONObject>,
    private val onBookmarkClick: (String) -> Unit,
    private val onBookmarkDelete: (String) -> Unit
) : RecyclerView.Adapter<BookmarksAdapter.BookmarkViewHolder>() {

    inner class BookmarkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView     = view.findViewById(R.id.tvBookmarkTitle)
        val tvUrl: TextView       = view.findViewById(R.id.tvBookmarkUrl)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteBookmark)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
        return BookmarkViewHolder(v)
    }

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        val bm = bookmarks[position]
        val url = bm.getString("url")
        holder.tvTitle.text = bm.optString("title", url)
        holder.tvUrl.text   = url
        holder.itemView.setOnClickListener { onBookmarkClick(url) }
        holder.btnDelete.setOnClickListener { onBookmarkDelete(url) }
    }

    override fun getItemCount() = bookmarks.size
}
