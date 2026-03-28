package com.sam.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

// ── Data model ────────────────────────────────────────────────────────────────

data class VideoFormat(
    val formatId: String,
    val ext: String,
    val resolution: String,
    val filesize: Long,
    val vcodec: String,
    val acodec: String,
    val tbr: Double,
    val fps: Int
) {
    val isAudioOnly get() = vcodec == "none" || vcodec.isBlank()
    val heightVal: Int get() = resolution.dropLast(1).toIntOrNull() ?: 0
    val displayLabel: String get() {
        val sz = if (filesize > 0) " ~${filesize / 1_048_576}MB" else ""
        val br = if (tbr > 0) " ${tbr.toInt()}kbps" else ""
        return if (isAudioOnly) "🎵 Audio · $ext$br$sz"
        else "🎬 $resolution · $ext${if (fps > 0 && fps != 30) " ${fps}fps" else ""}$sz"
    }
}

// ── Manager ───────────────────────────────────────────────────────────────────

object VideoDownloaderManager {

    private const val CHANNEL_ID = "sam_dl"
    private var initialized = false

    // ── Init ──────────────────────────────────────────────────────────────
    //
    // Call once in Application.onCreate() OR MainActivity.onCreate().
    // Always pass applicationContext — never an Activity context.
    //
    // FFmpeg.init() MUST come AFTER YoutubeDL.init().
    // It registers the bundled ffmpeg binary via env vars that yt-dlp reads.
    // Do NOT pass --ffmpeg-location in any request. Ever. The library handles it.

    fun init(ctx: Context) {
        if (initialized) return
        try {
            YoutubeDL.getInstance().init(ctx.applicationContext)
            FFmpeg.getInstance().init(ctx.applicationContext)
            initialized = true
        } catch (e: YoutubeDLException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── Update yt-dlp binary ──────────────────────────────────────────────
    //
    // Pulls latest yt-dlp from GitHub releases.
    // Returns true if binary is usable (updated or already current).
    // A failed update is non-fatal — the bundled binary still works.

    suspend fun updateYtDlp(
        ctx: Context,
        channel: YoutubeDL.UpdateChannel = YoutubeDL.UpdateChannel.STABLE,
        onStatus: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        ensureInit(ctx)
        return@withContext try {
            onStatus("Checking for yt-dlp update…")
            when (YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, channel)) {
                YoutubeDL.UpdateStatus.DONE -> {
                    onStatus("yt-dlp updated ✓"); true
                }
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> {
                    onStatus("yt-dlp already up to date ✓"); true
                }
                else -> { onStatus("Update check failed"); false }
            }
        } catch (e: Exception) {
            // Non-fatal — bundled binary still works
            onStatus("Update skipped: ${e.message?.take(40)}")
            true
        }
    }

    // ── Format listing ────────────────────────────────────────────────────
    //
    // Runs yt-dlp -j (JSON dump, no download) to list available formats.
    // Returns sorted list: best video first, audio-only at the bottom.
    // Empty list = caller should fall back to Quick Download buttons.

    suspend fun getFormats(ctx: Context, url: String): List<VideoFormat> =
        withContext(Dispatchers.IO) {
            ensureInit(ctx)
            return@withContext try {
                val request = YoutubeDLRequest(url).apply {
                    addOption("-j")
                    addOption("--no-playlist")
                    addOption("--socket-timeout", "15")
                }
                // Pass null for processId and callback — we just need the JSON output
                val response = YoutubeDL.getInstance().execute(request, null, null)
                if (response.out.isNullOrBlank()) return@withContext emptyList()
                parseFormats(JSONObject(response.out))
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
            if (ext in listOf("mhtml", "vtt", "json3")) continue
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
        return result.sortedWith(
            compareByDescending<VideoFormat> { if (it.isAudioOnly) -1 else it.heightVal }
                .thenByDescending { it.tbr }
        )
    }

    // ── Download ──────────────────────────────────────────────────────────
    //
    // Flow:
    //   1. Download into getExternalFilesDir() — app-private external dir.
    //      Requires ZERO permissions on ALL Android versions.
    //      Unlike cacheDir, the OS never auto-deletes it under storage pressure.
    //   2. On completion, move to public Downloads/SamBrowser via MediaStore
    //      (API 29+) or direct File copy (API 28-).
    //   3. Notification shows live %, speed, ETA and cancels on completion.
    //
    // [format] null  → best quality (bestvideo+bestaudio merged to mp4)
    // [audioOnly]    → best m4a (no video, no re-encode)
    //
    // !! Do NOT add --ffmpeg-location to any YoutubeDLRequest.
    //    FFmpeg.getInstance().init() already registered the bundled binary
    //    via environment variables. Passing --ffmpeg-location manually will
    //    point to the wrong path and break merging silently.

    fun startDownload(
        ctx: Context,
        url: String,
        format: VideoFormat?,
        audioOnly: Boolean = false,
        onDone: (success: Boolean, publicPath: String?) -> Unit = { _, _ -> }
    ) {
        ensureInit(ctx)
        createNotifChannel(ctx)
        val notifId = System.currentTimeMillis().toInt()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {

            // ── Format selector ───────────────────────────────────────────
            val fmtSel = when {
                audioOnly      -> "bestaudio[ext=m4a]/bestaudio[acodec=aac]/bestaudio"
                format == null -> "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
                format.isAudioOnly -> format.formatId
                else           -> "${format.formatId}+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
            }

            // ── Staging dir ───────────────────────────────────────────────
            val stagingDir = (ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: ctx.filesDir)  // fallback to internal storage if SD not mounted
                .resolve("staging")
                .also { it.mkdirs() }

            // Clean leftover files from any prior crashed download
            stagingDir.listFiles()?.forEach { it.delete() }

            val outTemplate = stagingDir.resolve("%(title).80s [%(id)s].%(ext)s").absolutePath

            // ── Build request ─────────────────────────────────────────────
            val request = YoutubeDLRequest(url).apply {
                addOption("-f", fmtSel)
                addOption("--no-playlist")
                addOption("--merge-output-format", if (audioOnly) "m4a" else "mp4")
                addOption("--concurrent-fragments", "8")   // parallel HLS/DASH segments
                addOption("--buffer-size", "64K")
                addOption("--http-chunk-size", "10M")
                addOption("--retries", "5")
                addOption("--fragment-retries", "5")
                addOption("--add-metadata")
                addOption("-o", outTemplate)
                // NO --ffmpeg-location — library injects it via env vars
            }

            // ── Notification ──────────────────────────────────────────────
            fun notify(text: String, pct: Int = -1, ongoing: Boolean = true) {
                val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(
                        if (ongoing) android.R.drawable.stat_sys_download
                        else android.R.drawable.stat_sys_download_done
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

            // ── Progress callback: (Float, Long, String) ──────────────────
            //   Float  = 0.0–100.0 percent
            //   Long   = ETA in seconds
            //   String = raw yt-dlp output line (contains speed info)
            val progressCallback: (Float, Long, String) -> Unit = { pct, eta, line ->
                val pctInt   = pct.toInt().coerceIn(0, 100)
                val etaStr   = if (eta > 0) " ETA ${formatEta(eta)}" else ""
                val speedStr = Regex("""at\s+(\S+/s)""").find(line)
                    ?.groupValues?.get(1)?.let { " · $it" } ?: ""
                notify("$pctInt%$speedStr$etaStr", pctInt)
            }

            // ── Execute ───────────────────────────────────────────────────
            val processId = "sam_dl_${System.nanoTime()}"
            try {
                YoutubeDL.getInstance().execute(request, processId, progressCallback)
            } catch (e: YoutubeDLException) {
                nm.cancel(notifId)
                notify("Failed: ${e.message?.take(80)}", ongoing = false)
                withContext(Dispatchers.Main) { onDone(false, null) }
                return@launch
            } catch (e: InterruptedException) {
                nm.cancel(notifId)
                withContext(Dispatchers.Main) { onDone(false, null) }
                return@launch
            } catch (e: Exception) {
                nm.cancel(notifId)
                notify("Error: ${e.message?.take(80)}", ongoing = false)
                withContext(Dispatchers.Main) { onDone(false, null) }
                return@launch
            }

            // ── Find the downloaded file ──────────────────────────────────
            val downloaded = stagingDir.listFiles()
                ?.filter { it.isFile && it.length() > 0 }
                ?.maxByOrNull { it.lastModified() }

            if (downloaded == null) {
                nm.cancel(notifId)
                notify("Download failed: output file missing", ongoing = false)
                withContext(Dispatchers.Main) { onDone(false, null) }
                return@launch
            }

            // ── Copy to public Downloads via MediaStore ────────────────────
            notify("Saving to Downloads…")
            val publicPath = copyToPublicDownloads(ctx, downloaded)
            downloaded.delete()   // always clean staging

            nm.cancel(notifId)
            notify("Done! ${downloaded.name}", pct = 100, ongoing = false)
            withContext(Dispatchers.Main) { onDone(publicPath != null, publicPath) }
        }
    }

    // ── MediaStore copy ───────────────────────────────────────────────────
    //
    // API 29+ → MediaStore.Downloads, IS_PENDING pattern (atomic, crash-safe)
    // API 28- → Direct File copy (WRITE_EXTERNAL_STORAGE already in manifest)

    private fun copyToPublicDownloads(ctx: Context, file: File): String? = try {
        val mime = guessMimeType(file.extension)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/SamBrowser")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver   = ctx.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null

            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } else {
            val dest = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "SamBrowser"
            ).also { it.mkdirs() }
            file.copyTo(File(dest, file.name), overwrite = true).absolutePath
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun ensureInit(ctx: Context) {
        if (!initialized) init(ctx.applicationContext)
    }

    private fun guessMimeType(ext: String) = when (ext.lowercase()) {
        "mp4"  -> "video/mp4"
        "webm" -> "video/webm"
        "mkv"  -> "video/x-matroska"
        "m4a"  -> "audio/mp4"
        "mp3"  -> "audio/mpeg"
        "opus" -> "audio/opus"
        "ogg"  -> "audio/ogg"
        "flac" -> "audio/flac"
        else   -> "application/octet-stream"
    }

    private fun formatEta(sec: Long) = if (sec / 60 > 0) "${sec / 60}m${sec % 60}s" else "${sec}s"

    private fun createNotifChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Video/audio download progress" }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}
