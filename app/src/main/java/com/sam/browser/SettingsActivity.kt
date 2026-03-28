package com.sam.browser

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.CookieManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "SamBrowserPrefs"
        private const val HOME_URL   = "https://www.google.com"
        // SeekBar steps 0-6 → percent values
        val TEXT_SIZE_STEPS = intArrayOf(75, 85, 100, 115, 130, 150, 175)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // ── Toolbar back button ───────────────────────────────────────────────
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // ── Security & Bypass ────────────────────────────────────────────────
        val switchBypass = findViewById<Switch>(R.id.switchBypass)
        val switchSniffer = findViewById<Switch>(R.id.switchSniffer)
        val switchAdBlock = findViewById<Switch>(R.id.switchAdBlock)
        val switchChrome  = findViewById<Switch>(R.id.switchChromeDisguise)

        switchBypass.isChecked  = prefs.getBoolean("bypass_enabled", true)
        switchSniffer.isChecked = prefs.getBoolean("sniffer_enabled", true)
        switchAdBlock.isChecked = prefs.getBoolean("adblock_enabled", true)
        switchChrome.isChecked  = prefs.getBoolean("chrome_disguise", false)

        switchBypass.setOnCheckedChangeListener  { _, c -> prefs.edit().putBoolean("bypass_enabled", c).apply() }
        switchSniffer.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("sniffer_enabled", c).apply() }
        switchAdBlock.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("adblock_enabled", c).apply() }
        switchChrome.setOnCheckedChangeListener  { _, c ->
            prefs.edit().putBoolean("chrome_disguise", c).apply()
            applyChromeDisguise(c)
        }

        // ── Browser toggles ──────────────────────────────────────────────────
        val switchBackBtn    = findViewById<Switch>(R.id.switchBackBtn)
        val switchTextSelect = findViewById<Switch>(R.id.switchTextSelect)

        switchBackBtn.isChecked    = prefs.getBoolean("hardware_back_enabled", true)
        switchTextSelect.isChecked = prefs.getBoolean("force_text_select", false)

        switchBackBtn.setOnCheckedChangeListener    { _, c -> prefs.edit().putBoolean("hardware_back_enabled", c).apply() }
        switchTextSelect.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("force_text_select", c).apply() }

        // ── Search Engine ────────────────────────────────────────────────────
        val rgSearch = findViewById<RadioGroup>(R.id.rgSearchEngine)
        when (prefs.getString("search_engine", "google")) {
            "ddg"  -> rgSearch.check(R.id.rbDDG)
            "bing" -> rgSearch.check(R.id.rbBing)
            else   -> rgSearch.check(R.id.rbGoogle)
        }
        rgSearch.setOnCheckedChangeListener { _, id ->
            val engine = when (id) {
                R.id.rbDDG  -> "ddg"
                R.id.rbBing -> "bing"
                else        -> "google"
            }
            prefs.edit().putString("search_engine", engine).apply()
        }

        // ── Text Size ────────────────────────────────────────────────────────
        val seekTextSize  = findViewById<SeekBar>(R.id.seekTextSize)
        val tvTextSizePct = findViewById<TextView>(R.id.tvTextSizePct)
        val savedPct      = prefs.getInt("text_size_pct", 100)
        val savedStep     = TEXT_SIZE_STEPS.indexOfFirst { it == savedPct }.let { if (it < 0) 2 else it }
        seekTextSize.progress = savedStep
        tvTextSizePct.text    = "$savedPct%"
        seekTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val pct = TEXT_SIZE_STEPS[p]
                tvTextSizePct.text = "$pct%"
                prefs.edit().putInt("text_size_pct", pct).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // ── Refresh Hold Delay ───────────────────────────────────────────────
        val seekDelay = findViewById<SeekBar>(R.id.seekRefreshDelay)
        val tvDelayMs = findViewById<TextView>(R.id.tvRefreshDelayMs)
        fun msFromProgress(p: Int) = (p + 1) * 100L
        fun progressFromMs(ms: Long) = ((ms / 100L) - 1).toInt().coerceIn(0, 19)
        val savedMs = prefs.getLong("refresh_hold_ms", 600L)
        seekDelay.progress = progressFromMs(savedMs)
        tvDelayMs.text     = "${savedMs}ms"
        seekDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val ms = msFromProgress(p)
                tvDelayMs.text = "${ms}ms"
                prefs.edit().putLong("refresh_hold_ms", ms).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // ── Homepage ─────────────────────────────────────────────────────────
        val tvHomepage = findViewById<TextView>(R.id.tvCurrentHomepage)
        tvHomepage.text = prefs.getString("home_url", HOME_URL)
        findViewById<LinearLayout>(R.id.menuSetHomepage).setOnClickListener {
            showHomepageDialog(prefs, tvHomepage)
        }

        // ── Privacy ──────────────────────────────────────────────────────────
        findViewById<LinearLayout>(R.id.menuClearHistory).setOnClickListener {
            DetectionLogger.clearHistory(this)
            DetectionLogger.clearScannedSites(this)
            DetectionLogger.clearNetworkLog(this)
            Toast.makeText(this, "History & logs cleared", Toast.LENGTH_SHORT).show()
        }
        findViewById<LinearLayout>(R.id.menuClearCache).setOnClickListener {
            // Flag picked up by MainActivity.onResume() to call webView.clearCache(true)
            prefs.edit().putBoolean("pending_clear_cache", true).apply()
            cacheDir.deleteRecursively()
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
        }
        findViewById<LinearLayout>(R.id.menuClearCookies).setOnClickListener {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            Toast.makeText(this, "Cookies cleared", Toast.LENGTH_SHORT).show()
        }
        findViewById<LinearLayout>(R.id.menuClearAll).setOnClickListener {
            DetectionLogger.clearHistory(this)
            DetectionLogger.clearScannedSites(this)
            DetectionLogger.clearNetworkLog(this)
            prefs.edit().putBoolean("pending_clear_cache", true).apply()
            cacheDir.deleteRecursively()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            Toast.makeText(this, "All data cleared", Toast.LENGTH_SHORT).show()
        }

        // Custom JS Scripts
        findViewById<LinearLayout>(R.id.menuCustomJs).setOnClickListener {
            startActivity(Intent(this, CustomJsActivity::class.java))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showHomepageDialog(
        prefs: android.content.SharedPreferences,
        tvHomepage: TextView
    ) {
        val input = EditText(this).apply {
            setText(prefs.getString("home_url", HOME_URL))
            selectAll()
            setPadding(48, 32, 48, 16)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or
                        android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            hint       = "https://..."
            background = null
        }
        val container = android.widget.FrameLayout(this).apply {
            addView(input)
            setBackgroundColor(0xFF2c2c30.toInt())
        }
        AlertDialog.Builder(this, R.style.HomepageDialogTheme)
            .setTitle("Set Homepage")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val raw = input.text.toString().trim()
                val url = when {
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    raw.isNotBlank() -> "https://$raw"
                    else             -> HOME_URL
                }
                prefs.edit().putString("home_url", url).apply()
                tvHomepage.text = url
                Toast.makeText(this, "Homepage set", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Reset") { _, _ ->
                prefs.edit().putString("home_url", HOME_URL).apply()
                tvHomepage.text = HOME_URL
                Toast.makeText(this, "Homepage reset", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun applyChromeDisguise(enable: Boolean) {
        val pm        = packageManager
        val samComp   = android.content.ComponentName(this, "com.sam.browser.MainActivity")
        val aliasComp = android.content.ComponentName(this, "com.sam.browser.ChromeAlias")
        if (enable) {
            pm.setComponentEnabledSetting(samComp,   PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(aliasComp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,  PackageManager.DONT_KILL_APP)
        } else {
            pm.setComponentEnabledSetting(aliasComp, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(samComp,   PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,  PackageManager.DONT_KILL_APP)
        }
        Toast.makeText(
            this,
            if (enable) "Now showing as Chrome in launcher" else "Restored as Sam Browser",
            Toast.LENGTH_SHORT
        ).show()
    }
}
