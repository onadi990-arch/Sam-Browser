package com.sam.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// ── Data model ────────────────────────────────────────────────────────────────

data class VideoFormat(
    val formatId: String,
    val ext: String,
    val resolution: String,   // e.g. "1080p", "720p", "audio only"
    val filesize: Long,        // bytes, 0 if unknown
    val vcodec: String,
    val acodec: String,
    val tbr: Double,           // total bitrate kbps
    val fps: Int
) {
    val isAudioOnly get() = vcodec == "none" || vcodec.isBlank()
    val displayLabel: String get() {
        val sz  = if (filesize > 0) " ~${filesize / 1_048_576}MB" else ""
        val br  = if (tbr > 0) " ${tbr.toInt()}kbps" else ""
        return if (isAudioOnly) "🎵 Audio · $ext$br$sz"
        else "🎬 $resolution · $ext${if (fps > 0 && fps != 30) " ${fps}fps" else ""}$sz"
    }
    val heightVal: Int get() = resolution.dropLast(1).toIntOrNull() ?: 0
}

// ── Manager ───────────────────────────────────────────────────────────────────

object VideoDownloaderManager {

    private const val CHANNEL_ID    = "sam_dl"
    private const val YTDLP_NAME    = "yt-dlp"
    // Official yt-dlp Android binary (ARM64)
    private const val YTDLP_DL_URL  =
        "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"

    // ── Binary management ─────────────────────────────────────────────────

    fun ytDlpBin(ctx: Context): File = File(ctx.filesDir, YTDLP_NAME)

    fun isInstalled(ctx: Context) = ytDlpBin(ctx).let { it.exists() && it.canExecute() }

    /**
     * Download yt-dlp binary from GitHub if missing.
     * Call from Dispatchers.IO. Returns true on success.
     */
    suspend fun ensureInstalled(
        ctx: Context,
        onStatus: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val bin = ytDlpBin(ctx)
        if (bin.exists() && bin.canExecute()) return@withContext true

        return@withContext try {
            onStatus("Downloading yt-dlp (one-time setup)…")
            val conn = URL(YTDLP_DL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout    = 120_000
            conn.connect()
            val total = conn.contentLengthLong
            var received = 0L

            conn.inputStream.use { inp ->
                FileOutputStream(bin).use { out ->
                    val buf = ByteArray(65_536)
                    var n: Int
                    while (inp.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        received += n
                        if (total > 0) {
                            val pct = (received * 100 / total).toInt()
                            onStatus("Downloading yt-dlp… $pct%")
                        }
                    }
                }
            }
            bin.setExecutable(true, false)
            onStatus("yt-dlp ready ✓")
            true
        } catch (e: Exception) {
            bin.delete()
            false
        }
    }

    // ── yt-dlp execution ──────────────────────────────────────────────────

    private fun run(ctx: Context, vararg args: String): Triple<String, String, Int> {
        val bin = ytDlpBin(ctx)
        return try {
            val proc = ProcessBuilder(listOf(bin.absolutePath) + args.toList())
                .directory(ctx.filesDir)
                .redirectErrorStream(false)
                .start()

            // Read both streams in parallel to prevent deadlock
            val pool  = Executors.newFixedThreadPool(2)
            val stdF  = pool.submit<String> { proc.inputStream.bufferedReader().readText() }
            val errF  = pool.submit<String> { proc.errorStream.bufferedReader().readText() }
            val code  = proc.waitFor()
            pool.shutdown()
            Triple(stdF.get(), errF.get(), code)
        } catch (e: Exception) {
            Triple("", e.message ?: "exec failed", -1)
        }
    }

    // ── Format listing ────────────────────────────────────────────────────

    /**
     * Fetch available formats for [url].
     * Returns a sorted list: best video first, then audio-only.
     * Adds a "Best (auto)" entry at the top.
     */
    suspend fun getFormats(ctx: Context, url: String): List<VideoFormat> =
        withContext(Dispatchers.IO) {
            val (stdout, _, code) = run(
                ctx,
                "-j", "--no-playlist", "--flat-playlist",
                "--socket-timeout", "15",
                url
            )
            if (code != 0 || stdout.isBlank()) return@withContext emptyList()
            try {
                parseFormats(JSONObject(stdout))
            } catch (e: Exception) {
                emptyList()
            }
        }

    private fun parseFormats(info: JSONObject): List<VideoFormat> {
        val arr = info.optJSONArray("formats") ?: return emptyList()
        val result = mutableListOf<VideoFormat>()

        for (i in 0 until arr.length()) {
            val f      = arr.getJSONObject(i)
            val ext    = f.optString("ext", "").trim()
            val vcodec = f.optString("vcodec", "none").trim()
            val acodec = f.optString("acodec", "none").trim()

            // Skip manifest/storyboard entries
            if (ext in listOf("mhtml", "vtt", "json3")) continue
            // Skip entries with neither video nor audio
            if (vcodec == "none" && acodec == "none") continue

            val height = f.optInt("height", 0)
            val resolution = when {
                vcodec == "none" || vcodec.isBlank() -> "audio only"
                height > 0 -> "${height}p"
                else -> f.optString("format_note", f.optString("format", "?"))
            }
            val filesize = f.optLong("filesize", 0L)
                .let { if (it == 0L) f.optLong("filesize_approx", 0L) else it }

            result.add(VideoFormat(
                formatId   = f.optString("format_id", ""),
                ext        = ext,
                resolution = resolution,
                filesize   = filesize,
                vcodec     = vcodec,
                acodec     = acodec,
                tbr        = f.optDouble("tbr", 0.0),
                fps        = f.optInt("fps", 0)
            ))
        }

        // Sort: video by height desc, then audio-only
        return result.sortedWith(
            compareByDescending<VideoFormat> { if (it.isAudioOnly) -1 else it.heightVal }
                .thenByDescending { it.tbr }
        )
    }

    // ── Download ──────────────────────────────────────────────────────────

    /**
     * Start download in background with notification progress.
     * [format] = null → best quality auto-selected.
     * [audioOnly] = true → best m4a audio.
     */
    fun startDownload(
        ctx: Context,
        url: String,
        format: VideoFormat?,
        audioOnly: Boolean = false,
        onDone: (success: Boolean, path: String?) -> Unit = { _, _ -> }
    ) {
        createNotifChannel(ctx)
        val notifId = System.currentTimeMillis().toInt()

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            // Build format selector
            val fmtSel = when {
                audioOnly ->
                    // Prefer native m4a, fallback to any best audio
                    "bestaudio[ext=m4a]/bestaudio[acodec=aac]/bestaudio/best"
                format == null ->
                    // Best video + audio merged to mp4
                    "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
                format.isAudioOnly ->
                    format.formatId
                else ->
                    "${format.formatId}+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
            }

            val outputDir = getDownloadsDir(ctx)
            outputDir.mkdirs()

            // Output filename template — yt-dlp handles collisions
            val outTpl = File(outputDir, "%(title).80s [%(id)s].%(ext)s").absolutePath

            fun notify(text: String, pct: Int = -1, ongoing: Boolean = true) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val b  = NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(
                        if (!ongoing) android.R.drawable.stat_sys_download_done
                        else android.R.drawable.stat_sys_download
                    )
                    .setContentTitle("SamBrowser Download")
                    .setContentText(text)
                    .setOngoing(ongoing)
                    .setSilent(true)
                if (pct in 0..100) b.setProgress(100, pct, false)
                else if (ongoing)  b.setProgress(0, 0, true)
                nm.notify(notifId, b.build())
            }

            notify("Starting…")

            val bin = ytDlpBin(ctx)
            val cmd = mutableListOf(
                bin.absolutePath,
                "-f",  fmtSel,
                "--no-playlist",
                // Speed: 8 parallel fragment workers
                "--concurrent-fragments", "8",
                "--buffer-size", "64K",
                "--http-chunk-size", "10M",
                "--retries", "5",
                "--fragment-retries", "5",
                // Merge video+audio into mp4; m4a stays m4a
                "--merge-output-format", if (audioOnly) "m4a" else "mp4",
                // Embed metadata
                "--add-metadata",
                // Output
                "-o", outTpl,
                url
            )

            try {
                val proc = ProcessBuilder(cmd)
                    .directory(outputDir)
                    .redirectErrorStream(true)
                    .start()

                val lines     = mutableListOf<String>()
                var finalPath: String? = null

                proc.inputStream.bufferedReader().forEachLine { line ->
                    lines.add(line)

                    // Progress: "[download]  45.3% of   50.00MiB at    2.50MiB/s ETA 00:12"
                    val pctM = Regex("""(\d+\.\d+)%""").find(line)
                    if (pctM != null) {
                        val pct = pctM.groupValues[1].toFloatOrNull()?.toInt() ?: 0
                        val eta = Regex("""ETA\s+(\S+)""").find(line)?.groupValues?.get(1) ?: ""
                        val spd = Regex("""at\s+(\S+/s)""").find(line)?.groupValues?.get(1) ?: ""
                        notify("$pct% · $spd${if (eta.isNotBlank()) " · ETA $eta" else ""}", pct)
                    }

                    // Detect output file
                    if (line.contains("[Merger] Merging formats into") ||
                        line.contains("[download] Destination:")) {
                        Regex(""""(.+?)"""").find(line)?.groupValues?.get(1)
                            ?.let { finalPath = it }
                            ?: Regex("""Destination:\s+(.+)""").find(line)
                                ?.groupValues?.get(1)
                                ?.let { finalPath = it.trim() }
                    }
                }

                val code = proc.waitFor()
                val nm   = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(notifId)

                if (code == 0) {
                    notify("Done! ${finalPath?.substringAfterLast('/') ?: ""}", ongoing = false)
                    withContext(Dispatchers.Main) { onDone(true, finalPath) }
                } else {
                    val err = lines.lastOrNull { it.isNotBlank() }?.take(120) ?: "Unknown error"
                    notify("Failed: $err", ongoing = false)
                    withContext(Dispatchers.Main) { onDone(false, null) }
                }
            } catch (e: Exception) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(notifId)
                withContext(Dispatchers.Main) { onDone(false, null) }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun getDownloadsDir(ctx: Context): File {
        val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(pub, "SamBrowser")
    }

    private fun createNotifChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Video/audio download progress" }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}
