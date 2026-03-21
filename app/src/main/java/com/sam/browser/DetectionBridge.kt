package com.sam.browser

import android.content.Context
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONArray

class DetectionBridge(
    private val context: Context,
    private val domain: String,
    private val url: String
) {

    @JavascriptInterface
    fun onDetectionComplete(resultsJson: String) {
        try {
            val arr = JSONArray(resultsJson)
            val findings = (0 until arr.length()).map { arr.getString(it) }
            DetectionLogger.writeLog(context, domain, url, findings)
            DetectionLogger.markScanned(context, domain)
            val msg = if (findings.isEmpty())
                "[$domain] No monitoring detected ✓"
            else
                "[$domain] ${findings.size} detection method(s) found — logged"
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * Called from the network sniffer script with a JSON object:
     * { ts, type, method, url, reqHeaders, reqBody, status, resHeaders, resBody, duration }
     */
    @JavascriptInterface
    fun onNetworkEvent(entryJson: String) {
        try {
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date())
            val entry = StringBuilder()
            entry.appendLine("────────────────────────────────────────")
            entry.append("[$ts] ")

            val obj = org.json.JSONObject(entryJson)
            val type     = obj.optString("type", "?")
            val method   = obj.optString("method", "GET").uppercase()
            val reqUrl   = obj.optString("url", "")
            val status   = obj.optInt("status", 0)
            val duration = obj.optLong("duration", -1)
            val reqBody  = obj.optString("reqBody", "")
            val resBody  = obj.optString("resBody", "")
            val reqHdr   = obj.optString("reqHeaders", "")
            val resHdr   = obj.optString("resHeaders", "")

            entry.appendLine("$type  $method  $reqUrl")
            if (reqHdr.isNotBlank()) entry.appendLine("  Req-Headers : $reqHdr")
            if (reqBody.isNotBlank()) entry.appendLine("  Req-Body    : ${reqBody.take(500)}")
            entry.appendLine("  Status      : $status${if (duration >= 0) "  (${duration}ms)" else ""}")
            if (resHdr.isNotBlank()) entry.appendLine("  Res-Headers : $resHdr")
            if (resBody.isNotBlank()) entry.appendLine("  Res-Body    : ${resBody.take(800)}")
            entry.appendLine()

            DetectionLogger.writeNetworkLog(context, entry.toString())
        } catch (e: Exception) { e.printStackTrace() }
    }
}
