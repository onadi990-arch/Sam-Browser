package com.sam.browser

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object DetectionLogger {

    private const val PREFS_NAME   = "SamBrowserPrefs"
    private const val SCAN_KEY     = "scanned_sites"
    private const val HISTORY_KEY  = "url_history"
    private const val MAX_HISTORY  = 50

    // ── Scanned sites ────────────────────────────────────────────────────────

    /** Returns true if this domain has already been scanned */
    fun isAlreadyScanned(context: Context, domain: String): Boolean {
        val set = getScannedSites(context)
        return set.contains(domain)
    }

    fun markScanned(context: Context, domain: String) {
        val set = getScannedSites(context).toMutableSet()
        set.add(domain)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(SCAN_KEY, set).apply()
    }

    private fun getScannedSites(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(SCAN_KEY, emptySet()) ?: emptySet()
    }

    fun clearScannedSites(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(SCAN_KEY).apply()
    }

    // ── Detection log file ───────────────────────────────────────────────────

    /**
     * Write a detection result to the log file.
     * File saved to: Downloads/SamBrowser/detection_log.txt
     */
    fun writeLog(context: Context, domain: String, url: String, findings: List<String>) {
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "SamBrowser"
            )
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, "detection_log.txt")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            val sb = StringBuilder()
            sb.appendLine("═══════════════════════════════════════")
            sb.appendLine("Time   : $ts")
            sb.appendLine("Domain : $domain")
            sb.appendLine("URL    : $url")
            sb.appendLine("Found  :")
            if (findings.isEmpty()) {
                sb.appendLine("  (none detected)")
            } else {
                findings.forEach { sb.appendLine("  • $it") }
            }
            sb.appendLine()

            file.appendText(sb.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── Network traffic log ──────────────────────────────────────────────────

    /**
     * Writes a captured network request+response to:
     * Downloads/SamBrowser/network_log.txt
     */
    fun writeNetworkLog(context: Context, entry: String) {
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "SamBrowser"
            )
            if (!dir.exists()) dir.mkdirs()
            File(dir, "network_log.txt").appendText(entry)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun clearNetworkLog(context: Context) {
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "SamBrowser"
            )
            File(dir, "network_log.txt").delete()
        } catch (e: Exception) {}
    }

    // ── URL History ──────────────────────────────────────────────────────────

    fun addHistory(context: Context, url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw   = prefs.getString(HISTORY_KEY, "[]") ?: "[]"
        val arr   = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }

        // Remove duplicate if exists
        val cleaned = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("url") != url) cleaned.put(obj)
        }

        // Prepend new entry
        val entry = JSONObject().apply {
            put("url", url)
            put("title", title.ifBlank { url })
            put("time", System.currentTimeMillis())
        }

        val final = JSONArray()
        final.put(entry)
        for (i in 0 until minOf(cleaned.length(), MAX_HISTORY - 1)) {
            final.put(cleaned.getJSONObject(i))
        }

        prefs.edit().putString(HISTORY_KEY, final.toString()).apply()
    }

    fun getHistory(context: Context): List<JSONObject> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw   = prefs.getString(HISTORY_KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun searchHistory(context: Context, query: String): List<JSONObject> {
        if (query.isBlank()) return getHistory(context).take(8)
        val q = query.lowercase()
        return getHistory(context).filter {
            it.optString("url").lowercase().contains(q) ||
            it.optString("title").lowercase().contains(q)
        }.take(8)
    }

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(HISTORY_KEY).apply()
    }
}
