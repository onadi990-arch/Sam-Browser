package com.sam.browser

import android.graphics.Bitmap
import android.view.View

data class TabData(
    val id: Int,
    var title: String = "New Tab",
    var url: String = "",
    var favicon: Bitmap? = null,
    val view: View,
    val webView: CustomWebView? = null,
    val isFileTab: Boolean = false,
    val fileType: String = ""   // "pdf" or "text" — used for restoring file tabs
)
