package com.sam.browser

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.File

class TextViewerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvTitle: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_viewer)

        webView  = findViewById(R.id.textWebView)
        tvTitle  = findViewById(R.id.tvTextTitle)

        findViewById<View>(R.id.btnTextBack).setOnClickListener { finish() }

        val uri = intent.data ?: run {
            webView.loadData("<p style='color:red'>No file provided</p>", "text/html", "UTF-8")
            return
        }

        tvTitle.text = getFileName(uri)
        loadText(uri)
    }

    private fun loadText(uri: Uri) {
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                try {
                    when (uri.scheme) {
                        "content" -> contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.readText() ?: "Unable to read file"
                        "file" -> File(uri.path ?: "").readText()
                        else -> "Unsupported URI scheme"
                    }
                } catch (e: Exception) {
                    "Error reading file: ${e.message}"
                }
            }

            val escaped = content
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

            val ext = getFileName(uri).substringAfterLast('.').lowercase()

            val html = buildHtml(escaped, ext)
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    }

    private fun buildHtml(content: String, ext: String): String {
        val bgColor   = "#1a1a1e"
        val textColor = "#e0e0e0"
        val font      = if (ext in listOf("json","xml","csv","log")) "monospace" else "'Segoe UI', sans-serif"

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body {
                    background: $bgColor;
                    color: $textColor;
                    font-family: $font;
                    font-size: 14px;
                    line-height: 1.6;
                    padding: 16px;
                    margin: 0;
                    word-break: break-word;
                    white-space: pre-wrap;
                }
                /* JSON/XML simple color hints */
                .key   { color: #4A90E2; }
                .str   { color: #7ec88a; }
                .num   { color: #e6c07b; }
            </style>
            </head>
            <body>$content</body>
            </html>
        """.trimIndent()
    }

    private fun getFileName(uri: Uri): String {
        return when (uri.scheme) {
            "content" -> {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && col >= 0) cursor.getString(col) else "File"
                } ?: "File"
            }
            "file" -> File(uri.path ?: "").name
            else -> "File"
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
