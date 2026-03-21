package com.sam.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvError: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)

        recyclerView = findViewById(R.id.pdfRecycler)
        progressBar  = findViewById(R.id.pdfProgress)
        tvTitle      = findViewById(R.id.tvPdfTitle)
        tvError      = findViewById(R.id.tvPdfError)

        findViewById<View>(R.id.btnPdfBack).setOnClickListener { finish() }

        recyclerView.layoutManager = LinearLayoutManager(this)

        val uri = intent.data ?: run {
            showError("No file provided")
            return
        }

        val fileName = getFileName(uri)
        tvTitle.text = fileName

        loadPdf(uri)
    }

    private fun loadPdf(uri: Uri) {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        tvError.visibility = View.GONE

        scope.launch {
            try {
                val pages = withContext(Dispatchers.IO) {
                    renderPdf(uri)
                }
                progressBar.visibility = View.GONE
                if (pages.isEmpty()) {
                    showError("Failed to render PDF")
                    return@launch
                }
                recyclerView.visibility = View.VISIBLE
                recyclerView.adapter = PdfPageAdapter(pages, pages.size)
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                showError("Error: ${e.message}")
            }
        }
    }

    private fun renderPdf(uri: Uri): List<Bitmap> {
        val pages = mutableListOf<Bitmap>()

        // Copy to temp file if needed (content URI)
        val pfd = when (uri.scheme) {
            "content" -> {
                val tmp = File(cacheDir, "temp_pdf.pdf")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            "file" -> {
                ParcelFileDescriptor.open(
                    File(uri.path ?: return pages),
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
            }
            else -> return pages
        }

        val renderer = PdfRenderer(pfd)
        val screenWidth = resources.displayMetrics.widthPixels

        for (i in 0 until renderer.pageCount) {
            val page = renderer.openPage(i)
            val scale = screenWidth.toFloat() / page.width
            val bitmapWidth  = (page.width  * scale).toInt()
            val bitmapHeight = (page.height * scale).toInt()
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            // White background
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            pages.add(bitmap)
        }

        renderer.close()
        pfd.close()
        return pages
    }

    private fun showError(msg: String) {
        tvError.visibility = View.VISIBLE
        tvError.text = msg
        recyclerView.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun getFileName(uri: Uri): String {
        return when (uri.scheme) {
            "content" -> {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && col >= 0) cursor.getString(col) else "Document"
                } ?: "Document"
            }
            "file" -> File(uri.path ?: "").name
            else -> "Document"
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
