package com.sam.browser

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PdfPageAdapter(
    private val pages: List<Bitmap>,
    private val totalPages: Int
) : RecyclerView.Adapter<PdfPageAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ZoomableImageView = view.findViewById(R.id.pdfPageImage)
        val tvPageNum: TextView = view.findViewById(R.id.tvPageNum)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_page, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.imageView.resetZoom()
        holder.imageView.setImageBitmap(pages[position])
        holder.tvPageNum.text = "${position + 1} / $totalPages"
    }

    override fun getItemCount() = pages.size
}
