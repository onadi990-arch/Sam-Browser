package com.sam.browser

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

/**
 * WebView that always reports it has window focus — bypasses
 * visibilitychange and blur events at the native level before
 * they ever reach JavaScript.
 */
class CustomWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(true)
    }

    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, android.view.View.VISIBLE)
    }

    // Prevent JS blur events firing when another view in the app gains focus
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(true, direction, previouslyFocusedRect)
    }
}
