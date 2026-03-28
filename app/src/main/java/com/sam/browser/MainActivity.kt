package com.sam.browser

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import android.widget.SeekBar
import android.widget.Switch

import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webViewContainer: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var addressBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnTabs: TextView
    private lateinit var btnMenu: ImageButton
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var btnFindPrev: ImageButton
    private lateinit var btnFindNext: ImageButton
    private lateinit var btnFindClose: ImageButton
    private lateinit var tvFindCount: TextView
    private lateinit var historyDropdown: LinearLayout
    private lateinit var historyRecycler: RecyclerView
    private lateinit var historyAdapter: HistoryAdapter

    private val tabs = mutableListOf<TabData>()
    private var currentTabIndex = 0
    private var tabIdCounter = 0
    private var isDesktopMode = false
    private var barsVisible = true
    private var bypassEnabled = true
    private var snifferEnabled = true
    private var hardwareBackEnabled = true
    private var adBlockEnabled = true
    private var forceTextSelectEnabled = false
    private var refreshHoldMs = 600L
    private var homeUrl = "https://www.google.com"
    private var chromeDisguiseEnabled = false
    private var searchEngine = "google"   // "google" | "ddg" | "bing"
    private var textSizePct  = 100        // 75 / 85 / 100 / 115 / 130 / 150 / 175
    private val SCROLL_THRESHOLD = 20
    private var topBarFullHeight = 0
    private var barAnimator: ViewPropertyAnimator? = null
    private var lastScrollMs = 0L
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val PREFS_NAME = "SamBrowserPrefs"
        const val BOOKMARKS_KEY = "bookmarks"
        const val TABS_KEY = "saved_tabs"
        const val HOME_URL = "https://www.google.com"
        const val STORAGE_PERMISSION_CODE = 101
    }

    // ── Bypass script ─────────────────────────────────────────────────────────
    private val bypassScript = """
        (function() {
            if (window.__samBypass) return;
            window.__samBypass = true;

            var _def = Object.defineProperty;

            // 1. Spoof visibility / focus
            _def(document, 'hidden',          { get: () => false,     configurable: true });
            _def(document, 'visibilityState', { get: () => 'visible', configurable: true });
            _def(document, 'hasFocus',        { value: () => true,    configurable: true });
            try { _def(document, 'mozHidden',    { get: () => false, configurable: true }); } catch(e) {}
            try { _def(document, 'webkitHidden', { get: () => false, configurable: true }); } catch(e) {}
            try { _def(document, 'mozVisibilityState',    { get: () => 'visible', configurable: true }); } catch(e) {}
            try { _def(document, 'webkitVisibilityState', { get: () => 'visible', configurable: true }); } catch(e) {}

            // 2. Always online
            try { _def(Navigator.prototype, 'onLine', { get: () => true, configurable: true }); } catch(e) {}

            // 3. Block detection events on ALL targets
            var BLOCKED = ['visibilitychange','blur','focusout','pagehide',
                           'freeze','beforeunload','offline','mouseleave'];
            var _origAdd = EventTarget.prototype.addEventListener;
            EventTarget.prototype.addEventListener = function(type, fn, opts) {
                if (BLOCKED.includes(type)) return;
                return _origAdd.call(this, type, fn, opts);
            };
            var _origDispatch = EventTarget.prototype.dispatchEvent;
            EventTarget.prototype.dispatchEvent = function(e) {
                if (BLOCKED.includes(e.type)) return true;
                return _origDispatch.call(this, e);
            };

            // 4. Kill inline handlers + re-run after DOM loads
            var _killHandlers = function() {
                var targets = [window, document, document.body, document.documentElement];
                targets.forEach(function(t) {
                    if (!t) return;
                    t.onblur = null; t.onfocusout = null;
                    t.onvisibilitychange = null; t.onpagehide = null;
                    t.onoffline = null; t.onfreeze = null; t.onbeforeunload = null;
                });
            };
            _killHandlers();
            document.addEventListener('DOMContentLoaded', _killHandlers, true);
            try {
                _def(window, 'onblur',  { get: () => null, set: () => {}, configurable: true });
                _def(window, 'onfocus', { get: () => null, set: () => {}, configurable: true });
            } catch(e) {}

            // 5. Counter disable-devtool.min.js
            // The library detects devtools via: window size diff, console.log timing,
            // debugger statement timing, and Firebug object. Null them all out.
            try { _def(window, 'outerWidth',  { get: () => window.innerWidth,  configurable: true }); } catch(e) {}
            try { _def(window, 'outerHeight', { get: () => window.innerHeight, configurable: true }); } catch(e) {}
            // Block the library's interval/timeout tricks
            var _origSetInterval = window.setInterval;
            var _origSetTimeout  = window.setTimeout;
            var DEVTOOL_PATTERNS = /disable.?devtool|disableDevtool|_disableDevtool/i;
            window.setInterval = function(fn, delay) {
                try {
                    var src = typeof fn === 'function' ? fn.toString() : String(fn);
                    if (DEVTOOL_PATTERNS.test(src)) return 0;
                } catch(e) {}
                return _origSetInterval.apply(this, arguments);
            };
            window.setTimeout = function(fn, delay) {
                try {
                    var src = typeof fn === 'function' ? fn.toString() : String(fn);
                    if (DEVTOOL_PATTERNS.test(src)) return 0;
                } catch(e) {}
                return _origSetTimeout.apply(this, arguments);
            };
            // Spoof console so timing-based detection fails
            var _noop = function() {};
            try {
                ['log','warn','error','info','debug','table','clear'].forEach(function(m) {
                    if (window.console && window.console[m]) window.console[m] = _noop;
                });
            } catch(e) {}

            // 6. Intercept ipify.org fetch — return school IP so page thinks you're local
            //    Only spoofs the network check, doesn't affect actual network routing.
            var SCHOOL_IP = '117.102.78.163';
            var _origFetch = window.fetch;
            window.fetch = function(input, init) {
                var url = (typeof input === 'string') ? input : (input && input.url) || '';
                if (url.includes('api.ipify.org') || url.includes('ipify')) {
                    return Promise.resolve(new Response(
                        JSON.stringify({ ip: SCHOOL_IP }),
                        { status: 200, headers: { 'Content-Type': 'application/json' } }
                    ));
                }
                return _origFetch.apply(this, arguments);
            };
            // Also intercept jQuery $.getJSON / XHR for ipify
            var _origXhrOpen = XMLHttpRequest.prototype.open;
            var _origXhrSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.__samUrl = url || '';
                return _origXhrOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                if (this.__samUrl && this.__samUrl.includes('ipify')) {
                    var self = this;
                    // Fake a successful response
                    Object.defineProperty(self, 'readyState', { get: () => 4 });
                    Object.defineProperty(self, 'status',    { get: () => 200 });
                    Object.defineProperty(self, 'responseText', { get: () => JSON.stringify({ ip: SCHOOL_IP }) });
                    Object.defineProperty(self, 'response',     { get: () => JSON.stringify({ ip: SCHOOL_IP }) });
                    setTimeout(function() {
                        if (typeof self.onreadystatechange === 'function') self.onreadystatechange();
                        if (typeof self.onload === 'function') self.onload();
                    }, 10);
                    return;
                }
                return _origXhrSend.apply(this, arguments);
            };
        })();
    """.trimIndent()

    // ── Detection sniffer script ──────────────────────────────────────────────
    private val snifferScript = """
        (function() {
            if (window.__samScanned) return;
            window.__samScanned = true;
            var findings = [];
            var _bridge = window.__svc;

            // ── Helper: send network event to Kotlin bridge ──────────────────
            function logNet(entry) {
                try {
                    if (_bridge && _bridge.onNetworkEvent) {
                        _bridge.onNetworkEvent(JSON.stringify(entry));
                    }
                } catch(e) {}
            }

            // ── 1. Intercept XHR — capture method, url, headers, body, response ──
            var _XHROpen  = XMLHttpRequest.prototype.open;
            var _XHRSetHdr = XMLHttpRequest.prototype.setRequestHeader;
            var _XHRSend  = XMLHttpRequest.prototype.send;

            XMLHttpRequest.prototype.open = function(method, url) {
                this.__sam_method = method;
                this.__sam_url    = url;
                this.__sam_reqHdr = {};
                this.__sam_t0     = Date.now();
                return _XHROpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.setRequestHeader = function(k, v) {
                if (this.__sam_reqHdr) this.__sam_reqHdr[k] = v;
                return _XHRSetHdr.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function(body) {
                var self = this;
                var t0 = Date.now();
                self.addEventListener('loadend', function() {
                    try {
                        var resHdr = {};
                        try {
                            self.getAllResponseHeaders().trim().split(/\r?\n/).forEach(function(line) {
                                var i = line.indexOf(':');
                                if (i > 0) resHdr[line.slice(0,i).trim()] = line.slice(i+1).trim();
                            });
                        } catch(e) {}
                        logNet({
                            type: 'XHR',
                            method: self.__sam_method || 'GET',
                            url: self.__sam_url || '',
                            reqHeaders: JSON.stringify(self.__sam_reqHdr || {}),
                            reqBody: body ? String(body).slice(0, 500) : '',
                            status: self.status,
                            resHeaders: JSON.stringify(resHdr),
                            resBody: (self.responseText || '').slice(0, 800),
                            duration: Date.now() - t0
                        });
                    } catch(e) {}
                });
                return _XHRSend.apply(this, arguments);
            };

            // ── 2. Intercept fetch — capture everything ──────────────────────
            var _origFetch = window.fetch;
            window.fetch = function(input, init) {
                var url    = typeof input === 'string' ? input : (input && input.url) || '';
                var method = (init && init.method) || (input && input.method) || 'GET';
                var reqHdr = {};
                try {
                    var h = (init && init.headers) || (input && input.headers);
                    if (h) {
                        if (h instanceof Headers) {
                            h.forEach(function(v,k){ reqHdr[k]=v; });
                        } else {
                            reqHdr = Object.assign({}, h);
                        }
                    }
                } catch(e) {}
                var reqBody = '';
                try { reqBody = (init && init.body) ? String(init.body).slice(0,500) : ''; } catch(e) {}
                var t0 = Date.now();

                return _origFetch.apply(this, arguments).then(function(res) {
                    var clone = res.clone();
                    var resHdr = {};
                    try { res.headers.forEach(function(v,k){ resHdr[k]=v; }); } catch(e) {}
                    clone.text().then(function(body) {
                        logNet({
                            type: 'fetch',
                            method: method.toUpperCase(),
                            url: url,
                            reqHeaders: JSON.stringify(reqHdr),
                            reqBody: reqBody,
                            status: res.status,
                            resHeaders: JSON.stringify(resHdr),
                            resBody: body.slice(0, 800),
                            duration: Date.now() - t0
                        });
                    }).catch(function(){});
                    return res;
                }).catch(function(err) {
                    logNet({
                        type: 'fetch',
                        method: method.toUpperCase(),
                        url: url,
                        reqHeaders: JSON.stringify(reqHdr),
                        reqBody: reqBody,
                        status: 0,
                        resHeaders: '{}',
                        resBody: 'ERROR: ' + String(err),
                        duration: Date.now() - t0
                    });
                    throw err;
                });
            };

            // ── 3. Detection event findings (original sniffer) ───────────────
            var _origAdd = EventTarget.prototype.addEventListener;
            var monitoredEvents = ['visibilitychange','blur','focusout','focus','pagehide','freeze'];
            EventTarget.prototype.addEventListener = function(type, fn, opts) {
                if (monitoredEvents.includes(type)) {
                    findings.push('addEventListener: "' + type + '" on ' + (this === window ? 'window' : this === document ? 'document' : 'element'));
                }
                return _origAdd.call(this, type, fn, opts);
            };

            // ── 4. navigator.onLine access ───────────────────────────────────
            var _onlineDesc = Object.getOwnPropertyDescriptor(Navigator.prototype, 'onLine');
            if (_onlineDesc) {
                Object.defineProperty(Navigator.prototype, 'onLine', {
                    get: function() {
                        findings.push('navigator.onLine accessed');
                        return _onlineDesc.get.call(this);
                    }
                });
            }

            // ── 5. Report detection findings after 5s ────────────────────────
            setTimeout(function() {
                var unique = findings.filter(function(v,i,a){ return a.indexOf(v) === i; });
                if (_bridge && _bridge.onDetectionComplete) {
                    _bridge.onDetectionComplete(JSON.stringify(unique));
                }
            }, 5000);
        })();
    """.trimIndent()

    // ── Force text selection script ────────────────────────────────────────────
    // Injected in onPageFinished. Overrides CSS user-select and neutralises
    // JS handlers that steal long-press / contextmenu / selectstart events.
    private val forceTextSelectScript = """
        (function() {
            // 1. Inject a <style> that forces user-select on every element
            var style = document.getElementById('__sam_textsel');
            if (!style) {
                style = document.createElement('style');
                style.id = '__sam_textsel';
                style.textContent = [
                    '*, *::before, *::after {',
                    '  -webkit-user-select: text !important;',
                    '  user-select: text !important;',
                    '  -webkit-touch-callout: default !important;',
                    '}'
                ].join('');
                document.head.appendChild(style);
            }
            // 2. Remove JS-based selection blockers on document and window
            var killEvents = ['selectstart','contextmenu','copy','cut','dragstart'];
            killEvents.forEach(function(evt) {
                document.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
                window.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
            });
            // 3. Null out common inline blockers set directly on document/body
            document.onselectstart = null;
            document.oncontextmenu = null;
            document.oncopy = null;
            if (document.body) {
                document.body.onselectstart = null;
                document.body.oncontextmenu = null;
                document.body.oncopy = null;
            }
        })();
    """.trimIndent()

    private val mobileUA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Enable cookies globally
        CookieManager.getInstance().setAcceptCookie(true)

        requestStoragePermission()
        initViews()
        setupListeners()
        setupHistoryDropdown()
        loadScriptPrefs()

        // Restore saved tabs first
        val restored = restoreTabs()

        // Handle incoming file intent (opened from file manager)
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            handleFileIntent(intent)
        } else if (!restored) {
            createNewTab(homeUrl)
        }

        // Init youtubedl-android (bundles yt-dlp + FFmpeg — no manual download needed)
        VideoDownloaderManager.init(this)

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    102
                )
            }
        }

        // After first layout: record bar height, pad webview so content starts below bar
        topBar.post {
            topBarFullHeight = topBar.height
            webViewContainer.setPadding(0, topBarFullHeight, 0, 0)
            // Position history dropdown just below the bar
            (historyDropdown.layoutParams as? android.widget.FrameLayout.LayoutParams)
                ?.topMargin = topBarFullHeight
            historyDropdown.requestLayout()
        }
    }

    private fun loadScriptPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        bypassEnabled        = prefs.getBoolean("bypass_enabled", true)
        snifferEnabled       = prefs.getBoolean("sniffer_enabled", false)
        hardwareBackEnabled  = prefs.getBoolean("hardware_back_enabled", true)
        adBlockEnabled       = prefs.getBoolean("adblock_enabled", true)
        forceTextSelectEnabled = prefs.getBoolean("force_text_select", false)
        refreshHoldMs        = prefs.getLong("refresh_hold_ms", 600L)
        homeUrl              = prefs.getString("home_url", HOME_URL) ?: HOME_URL
        chromeDisguiseEnabled = prefs.getBoolean("chrome_disguise", false)
        searchEngine         = prefs.getString("search_engine", "google") ?: "google"
        textSizePct          = prefs.getInt("text_size_pct", 100)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            handleFileIntent(intent)
        }
    }

    private fun handleFileIntent(intent: Intent) {
        val uri = intent.data ?: return
        val mime = contentResolver.getType(uri) ?: ""
        when {
            mime == "application/pdf" || uri.toString().endsWith(".pdf", true) ->
                createPdfTab(uri)
            mime.startsWith("text/") || mime == "application/json" ||
            uri.toString().let { it.endsWith(".txt",true) || it.endsWith(".json",true) || it.endsWith(".csv",true) } ->
                createTextTab(uri)
            else -> createNewTab(uri.toString())
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    STORAGE_PERMISSION_CODE
                )
            }
        }
    }

    private fun initViews() {
        webViewContainer = findViewById(R.id.webViewContainer)
        topBar           = findViewById(R.id.topBar)
        addressBar       = findViewById(R.id.addressBar)
        progressBar      = findViewById(R.id.progressBar)
        btnBack          = findViewById(R.id.btnBack)
        btnForward       = findViewById(R.id.btnForward)
        btnRefresh       = findViewById(R.id.btnRefresh)
        btnTabs          = findViewById(R.id.btnTabs)
        btnMenu          = findViewById(R.id.btnMenu)
        findBar          = findViewById(R.id.findBar)
        findInput        = findViewById(R.id.findInput)
        btnFindPrev      = findViewById(R.id.btnFindPrev)
        btnFindNext      = findViewById(R.id.btnFindNext)
        btnFindClose     = findViewById(R.id.btnFindClose)
        tvFindCount      = findViewById(R.id.tvFindCount)
        historyDropdown  = findViewById(R.id.historyDropdown)
        historyRecycler  = findViewById(R.id.historyRecycler)
    }

    private fun setupListeners() {
        addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                addressBar.selectAll()
                showHistoryDropdown("")
            } else {
                hideHistoryDropdown()
            }
        }

        addressBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (addressBar.isFocused) showHistoryDropdown(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        addressBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                loadUrl(addressBar.text.toString())
                true
            } else false
        }

        btnBack.setOnClickListener {
            if (currentWebView()?.canGoBack() == true) {
                currentWebView()?.goBack()
                showBars()
            }
        }
        btnForward.setOnClickListener {
            if (currentWebView()?.canGoForward() == true) currentWebView()?.goForward()
        }
        // Long-press refresh — must hold refreshHoldMs to reload.
        // Short tap does nothing. Only the held press fires.
        val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var refreshRunnable: Runnable? = null

        btnRefresh.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Start a hold timer — fill progress ring visually via alpha
                    btnRefresh.animate().alpha(0.4f).setDuration(refreshHoldMs).start()
                    refreshRunnable = Runnable {
                        btnRefresh.animate().cancel()
                        btnRefresh.alpha = 1f
                        currentWebView()?.reload()
                        // Pulse feedback
                        btnRefresh.animate()
                            .scaleX(1.3f).scaleY(1.3f).setDuration(80)
                            .withEndAction {
                                btnRefresh.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                            }.start()
                    }
                    refreshHandler.postDelayed(refreshRunnable!!, refreshHoldMs)
                    v.isPressed = true
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    // Released before hold completed — cancel, reset visual
                    refreshHandler.removeCallbacks(refreshRunnable!!)
                    btnRefresh.animate().cancel()
                    btnRefresh.alpha = 1f
                    btnRefresh.scaleX = 1f
                    btnRefresh.scaleY = 1f
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
        btnTabs.setOnClickListener { showTabSwitcher() }
        btnMenu.setOnClickListener { showMenu() }

        findInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                if (query.isNotEmpty()) currentWebView()?.findAllAsync(query)
                else currentWebView()?.clearMatches()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        btnFindPrev.setOnClickListener { currentWebView()?.findNext(false) }
        btnFindNext.setOnClickListener { currentWebView()?.findNext(true) }
        btnFindClose.setOnClickListener { closeFindBar() }
    }

    // ── History dropdown ──────────────────────────────────────────────────────

    private fun setupHistoryDropdown() {
        historyRecycler.layoutManager = LinearLayoutManager(this)
        historyAdapter = HistoryAdapter(
            items = emptyList(),
            onItemClick = { url ->
                loadUrl(url)
                hideHistoryDropdown()
            },
            onDeleteClick = { url ->
                deleteHistoryItem(url)
            }
        )
        historyRecycler.adapter = historyAdapter
    }

    private fun showHistoryDropdown(query: String) {
        val results = DetectionLogger.searchHistory(this, query)
        if (results.isEmpty()) {
            hideHistoryDropdown()
            return
        }
        historyAdapter.update(results)
        historyDropdown.visibility = View.VISIBLE
    }

    private fun hideHistoryDropdown() {
        historyDropdown.visibility = View.GONE
    }

    private fun deleteHistoryItem(url: String) {
        // Remove single item from history
        val all = DetectionLogger.getHistory(this).toMutableList()
        val filtered = all.filter { it.optString("url") != url }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        filtered.forEach { arr.put(it) }
        prefs.edit().putString("url_history", arr.toString()).apply()
        showHistoryDropdown(addressBar.text.toString())
    }

    // ── Bars show/hide ────────────────────────────────────────────────────────
    // Uses translationY only — no requestLayout, no height changes, zero jank.
    // WebView has paddingTop = topBar height so content starts below bar.
    // Hiding slides bar up via translationY; WebView padding stays, content
    // just becomes visible under where the bar was (exactly how Chrome works).

    private fun showBars() {
        if (barsVisible) return
        barsVisible = true
        barAnimator?.cancel()
        barAnimator = null
        topBar.animate()
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hideBars() {
        if (!barsVisible) return
        if (topBarFullHeight == 0) topBarFullHeight = topBar.height
        barsVisible = false
        barAnimator?.cancel()
        barAnimator = null
        topBar.animate()
            .translationY(-topBarFullHeight.toFloat())
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // ── Desktop mode ──────────────────────────────────────────────────────────

    fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        val ua = if (isDesktopMode) desktopUA else mobileUA
        tabs.forEach { tab ->
            tab.webView?.let { wv ->
                wv.settings.userAgentString = ua
                wv.settings.useWideViewPort = isDesktopMode
                wv.reload()
            }
        }
    }

    // ── Tab creation ──────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    fun createNewTab(url: String = homeUrl): TabData {
        val webView = CustomWebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                textZoom = textSizePct
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                allowFileAccess = true
                databaseEnabled = true
                // Cache: serve from cache when available, hit network only if stale
                cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = if (isDesktopMode) desktopUA else mobileUA
                // Speed tweaks
                setSafeBrowsingEnabled(false)   // removes a round-trip DNS check per nav
                setGeolocationEnabled(false)    // saves a permission prompt + background work
                mediaPlaybackRequiresUserGesture = true
                // Let the browser handle encoding detection instead of guessing
                defaultTextEncodingName = "UTF-8"
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    // 1. Inject bypass script immediately (if enabled)
                    if (bypassEnabled) {
                        view.evaluateJavascript(bypassScript, null)
                    }

                    // 2. Inject sniffer only if enabled and domain not yet scanned
                    if (snifferEnabled) {
                        val domain = getDomain(url)
                        if (domain.isNotBlank() && !DetectionLogger.isAlreadyScanned(this@MainActivity, domain)) {
                            view.addJavascriptInterface(
                                DetectionBridge(this@MainActivity, domain, url),
                                "__svc"
                            )
                            view.evaluateJavascript(snifferScript, null)
                        }
                    }

                    // Inject custom "start" scripts
                    CustomJsManager.forStage(this@MainActivity, "start").forEach { s ->
                        view.evaluateJavascript(s.code, null)
                    }

                    // Cosmetic ad filter (CSS element hiding) — early injection
                    if (adBlockEnabled) {
                        view.evaluateJavascript(CosmeticFilter.buildScript(), null)
                    }

                    tabs.find { it.webView === view }?.url = url
                    if (view === currentWebView()) {
                        addressBar.setText(url)
                        hideHistoryDropdown()
                        updateNavButtons()
                        showBars()
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    val title = view.title ?: url
                    tabs.find { it.webView === view }?.apply {
                        this.url = url
                        this.title = title
                    }
                    if (view === currentWebView()) {
                        addressBar.setText(url)
                        updateNavButtons()
                    }
                    DetectionLogger.addHistory(this@MainActivity, url, title)

                    // Re-inject bypass on every page finish (covers SPA hash navigations
                    // where onPageStarted doesn't re-fire but the page may reset handlers)
                    if (bypassEnabled) {
                        view.evaluateJavascript(bypassScript, null)
                    }

                    if (forceTextSelectEnabled) {
                        view.evaluateJavascript(forceTextSelectScript, null)
                    }

                    // Inject custom "finish" scripts
                    CustomJsManager.forStage(this@MainActivity, "finish").forEach { s ->
                        view.evaluateJavascript(s.code, null)
                    }

                    // Cosmetic ad filter — re-inject on finish to catch late-loaded ads
                    if (adBlockEnabled) {
                        view.evaluateJavascript(CosmeticFilter.buildScript(), null)
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    // Intercept PDF links — open as tab
                    if (url.endsWith(".pdf", ignoreCase = true) ||
                        url.contains(".pdf?", ignoreCase = true)) {
                        createPdfTab(Uri.parse(url))
                        return true
                    }
                    // Intercept text/data file links — open as tab
                    val textExts = listOf(".txt", ".csv", ".json", ".xml", ".log", ".md")
                    if (textExts.any { url.endsWith(it, ignoreCase = true) }) {
                        createTextTab(Uri.parse(url))
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ): android.webkit.WebResourceResponse? {
                    if (adBlockEnabled) {
                        AdBlocker.shouldBlock(request.url.toString())?.let { return it }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (view == currentWebView()) {
                        progressBar.progress = newProgress
                        progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                    }
                }
                override fun onReceivedTitle(view: WebView, title: String) {
                    tabs.find { it.webView === view }?.title = title
                }
                override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                    tabs.find { it.webView === view }?.favicon = icon
                }
            }

            // Scroll-based navbar hide/show — throttled to prevent animation thrash
            setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                if (this != currentWebView()) return@setOnScrollChangeListener
                val now = System.currentTimeMillis()
                if (now - lastScrollMs < 80) return@setOnScrollChangeListener
                lastScrollMs = now
                val delta = scrollY - oldScrollY
                when {
                    delta > SCROLL_THRESHOLD -> hideBars()
                    delta < -SCROLL_THRESHOLD || scrollY == 0 -> showBars()
                }
            }

            setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                tvFindCount.text = if (numberOfMatches > 0)
                    "${activeMatchOrdinal + 1}/$numberOfMatches" else "0/0"
            }
        }

        val tab = TabData(tabIdCounter++, view = webView, webView = webView)
        tabs.add(tab)
        webViewContainer.addView(webView)
        switchToTab(tabs.size - 1)
        webView.loadUrl(normalizeUrl(url))
        updateTabCount()
        return tab
    }

    // ── File tab creation ─────────────────────────────────────────────────────

    private fun createPdfTab(uri: Uri) {
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#1a1a1e"))
        }

        val progress = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER }
        }
        container.addView(progress)

        val recycler = RecyclerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#1a1a1e"))
            layoutManager = LinearLayoutManager(this@MainActivity)
            visibility = View.GONE
        }
        container.addView(recycler)

        val errorTv = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER }
            setTextColor(Color.parseColor("#e53935"))
            textSize = 14f
            setPadding(48, 48, 48, 48)
            visibility = View.GONE
        }
        container.addView(errorTv)

        val fileName = getFileNameFromUri(uri)
        val cachedPath = copyToPersistentCache(uri, "pdf")
        val tab = TabData(
            id = tabIdCounter++,
            title = fileName,
            url = cachedPath ?: uri.toString(),
            view = container,
            isFileTab = true,
            fileType = "pdf"
        )
        tabs.add(tab)
        webViewContainer.addView(container)
        switchToTab(tabs.size - 1)
        updateTabCount()

        // Render PDF in background
        scope.launch {
            try {
                val renderUri = if (cachedPath != null) Uri.fromFile(File(cachedPath)) else uri
                val pages = withContext(Dispatchers.IO) {
                    renderPdfPages(renderUri)
                }
                progress.visibility = View.GONE
                if (pages.isEmpty()) {
                    errorTv.text = "Failed to render PDF"
                    errorTv.visibility = View.VISIBLE
                } else {
                    recycler.visibility = View.VISIBLE
                    recycler.adapter = PdfPageAdapter(pages, pages.size)
                }
            } catch (e: Exception) {
                progress.visibility = View.GONE
                errorTv.text = "Error: ${e.message}"
                errorTv.visibility = View.VISIBLE
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTextTab(uri: Uri) {
        val fileName = getFileNameFromUri(uri)
        val isMd = fileName.endsWith(".md", ignoreCase = true)

        val textWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#1a1a1e"))
            settings.javaScriptEnabled = isMd  // JS needed for inline MD parser
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }

        val cachedPath = copyToPersistentCache(uri, "text")
        val tab = TabData(
            id = tabIdCounter++,
            title = fileName,
            url = cachedPath ?: uri.toString(),
            view = textWebView,
            isFileTab = true,
            fileType = "text"
        )
        tabs.add(tab)
        webViewContainer.addView(textWebView)
        switchToTab(tabs.size - 1)
        updateTabCount()

        // Load text in background
        scope.launch {
            val readUri = if (cachedPath != null) Uri.fromFile(File(cachedPath)) else uri
            val content = withContext(Dispatchers.IO) {
                try {
                    when (readUri.scheme) {
                        "content" -> contentResolver.openInputStream(readUri)
                            ?.bufferedReader()?.readText() ?: "Unable to read file"
                        "file" -> File(readUri.path ?: "").readText()
                        "http", "https" -> URL(readUri.toString()).readText()
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

            val ext = fileName.substringAfterLast('.').lowercase()
            val html = buildTextHtml(escaped, ext)
            textWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    }

    private fun renderPdfPages(uri: Uri): List<Bitmap> {
        val pages = mutableListOf<Bitmap>()
        val uniqueName = "pdf_${System.nanoTime()}.pdf"
        val pfd: ParcelFileDescriptor = when (uri.scheme) {
            "content" -> {
                val tmp = File(cacheDir, uniqueName)
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
            "http", "https" -> {
                val tmp = File(cacheDir, uniqueName)
                URL(uri.toString()).openStream().use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
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
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            pages.add(bitmap)
        }

        renderer.close()
        pfd.close()
        return pages
    }

    private fun buildTextHtml(content: String, ext: String): String {
        val bgColor   = "#1a1a1e"
        val textColor = "#e0e0e0"

        if (ext == "md") return buildMarkdownHtml(content)

        val font = if (ext in listOf("json","xml","csv","log")) "monospace" else "'Segoe UI', sans-serif"

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
            </style>
            </head>
            <body>$content</body>
            </html>
        """.trimIndent()
    }

    private fun buildMarkdownHtml(rawContent: String): String {
        // rawContent is already HTML-escaped (&amp; &lt; &gt;)
        // We pass it as a JS string and let the inline parser work on the
        // UN-escaped version, so we unescape first inside JS.
        val jsEscaped = rawContent
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("\$", "\\\$")

        return """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  body { background:#1a1a1e; color:#e0e0e0; font-family:'Segoe UI',sans-serif;
         font-size:14px; line-height:1.7; padding:16px; margin:0; word-break:break-word; }
  h1 { font-size:1.8em; border-bottom:1px solid #3a3a3e; padding-bottom:6px; margin-top:24px; color:#fff; }
  h2 { font-size:1.5em; border-bottom:1px solid #3a3a3e; padding-bottom:4px; margin-top:20px; color:#fff; }
  h3 { font-size:1.25em; margin-top:18px; color:#fff; }
  h4,h5,h6 { font-size:1.1em; margin-top:16px; color:#ccc; }
  a { color:#4A90E2; text-decoration:none; }
  a:hover { text-decoration:underline; }
  code { background:#2a2a30; padding:2px 6px; border-radius:4px; font-family:monospace; font-size:0.92em; }
  pre { background:#2a2a30; padding:12px; border-radius:6px; overflow-x:auto; }
  pre code { background:none; padding:0; }
  blockquote { border-left:3px solid #4A90E2; margin:12px 0; padding:4px 16px; color:#aaa; }
  hr { border:none; border-top:1px solid #3a3a3e; margin:20px 0; }
  table { border-collapse:collapse; width:100%; margin:12px 0; }
  th,td { border:1px solid #3a3a3e; padding:8px 12px; text-align:left; }
  th { background:#2a2a30; color:#fff; }
  img { max-width:100%; border-radius:6px; }
  ul,ol { padding-left:24px; }
  li { margin:4px 0; }
  .task-done { text-decoration:line-through; color:#888; }
</style>
</head>
<body><div id="out"></div>
<script>
var raw = `$jsEscaped`;
// Unescape HTML entities the Kotlin side added
raw = raw.replace(/&amp;/g,'&').replace(/&lt;/g,'<').replace(/&gt;/g,'>');

function md(s) {
  // Code blocks (fenced)
  s = s.replace(/```(\w*)\n([\s\S]*?)```/g, function(m,lang,code) {
    return '<pre><code>' + code.replace(/</g,'&lt;').replace(/>/g,'&gt;') + '</code></pre>';
  });
  // Headings
  s = s.replace(/^#{6}\s+(.+)$/gm,'<h6>$1</h6>');
  s = s.replace(/^#{5}\s+(.+)$/gm,'<h5>$1</h5>');
  s = s.replace(/^#{4}\s+(.+)$/gm,'<h4>$1</h4>');
  s = s.replace(/^###\s+(.+)$/gm,'<h3>$1</h3>');
  s = s.replace(/^##\s+(.+)$/gm,'<h2>$1</h2>');
  s = s.replace(/^#\s+(.+)$/gm,'<h1>$1</h1>');
  // Horizontal rules
  s = s.replace(/^(\*{3,}|-{3,}|_{3,})$/gm,'<hr>');
  // Bold + Italic
  s = s.replace(/\*\*\*(.+?)\*\*\*/g,'<b><i>$1</i></b>');
  s = s.replace(/\*\*(.+?)\*\*/g,'<b>$1</b>');
  s = s.replace(/\*(.+?)\*/g,'<i>$1</i>');
  s = s.replace(/__(.+?)__/g,'<b>$1</b>');
  s = s.replace(/_(.+?)_/g,'<i>$1</i>');
  s = s.replace(/~~(.+?)~~/g,'<s>$1</s>');
  // Inline code
  s = s.replace(/`([^`]+)`/g,'<code>$1</code>');
  // Images
  s = s.replace(/!\[([^\]]*)\]\(([^)]+)\)/g,'<img alt="$1" src="$2">');
  // Links
  s = s.replace(/\[([^\]]+)\]\(([^)]+)\)/g,'<a href="$2">$1</a>');
  // Blockquotes
  s = s.replace(/^>\s+(.+)$/gm,'<blockquote>$1</blockquote>');
  // Task lists
  s = s.replace(/^\s*[-*]\s+\[x\]\s+(.+)$/gm,'<li class="task-done">&#9745; $1</li>');
  s = s.replace(/^\s*[-*]\s+\[ \]\s+(.+)$/gm,'<li>&#9744; $1</li>');
  // Unordered lists
  s = s.replace(/^\s*[-*]\s+(.+)$/gm,'<li>$1</li>');
  // Ordered lists
  s = s.replace(/^\s*\d+\.\s+(.+)$/gm,'<li>$1</li>');
  // Wrap consecutive <li> in <ul>
  s = s.replace(/((?:<li[^>]*>.*<\/li>\s*)+)/g,'<ul>$1</ul>');
  // Tables
  s = s.replace(/^\|(.+)\|\s*\n\|[\s|:-]+\|\s*\n((?:\|.+\|\s*\n?)*)/gm, function(m,hdr,body) {
    var ths = hdr.split('|').map(function(c){return '<th>'+c.trim()+'</th>';}).join('');
    var rows = body.trim().split('\n').map(function(r) {
      var tds = r.replace(/^\||\|$/g,'').split('|').map(function(c){return '<td>'+c.trim()+'</td>';}).join('');
      return '<tr>'+tds+'</tr>';
    }).join('');
    return '<table><thead><tr>'+ths+'</tr></thead><tbody>'+rows+'</tbody></table>';
  });
  // Paragraphs — double newlines
  s = s.replace(/\n{2,}/g,'</p><p>');
  s = '<p>' + s + '</p>';
  // Clean up empty paragraphs around block elements
  s = s.replace(/<p>\s*(<h[1-6]|<pre|<blockquote|<ul|<hr|<table)/g,'$1');
  s = s.replace(/(<\/h[1-6]>|<\/pre>|<\/blockquote>|<\/ul>|<hr>|<\/table>)\s*<\/p>/g,'$1');
  // Line breaks
  s = s.replace(/\n/g,'<br>');
  return s;
}
document.getElementById('out').innerHTML = md(raw);
</script>
</body>
</html>
        """.trimIndent()
    }

    private fun getFileNameFromUri(uri: Uri): String {
        return when (uri.scheme) {
            "content" -> {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && col >= 0) cursor.getString(col) else "Document"
                } ?: "Document"
            }
            "file" -> File(uri.path ?: "").name
            "http", "https" -> {
                val path = uri.lastPathSegment ?: "Document"
                if (path.contains('.')) path else "Document"
            }
            else -> "Document"
        }
    }

    // ── Tab management ────────────────────────────────────────────────────────

    fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        // Move all tabs off-screen via translationX — keeps visibility=VISIBLE so
        // the WebView never gets a focus-loss event (GONE would trigger it)
        tabs.forEach { it.view.translationX = -99999f }
        currentTabIndex = index
        val tab = tabs[index]
        tab.view.translationX = 0f

        if (tab.isFileTab) {
            addressBar.setText(tab.title)
            addressBar.isFocusable = false
            addressBar.isFocusableInTouchMode = false
            addressBar.clearFocus()
        } else {
            addressBar.isFocusable = true
            addressBar.isFocusableInTouchMode = true
            addressBar.setText(tab.url)
        }

        updateNavButtons()
        updateTabCount()
        showBars()
    }

    fun closeTab(index: Int) {
        if (tabs.size == 1 && !tabs[0].isFileTab) {
            tabs[0].webView?.loadUrl(homeUrl)
            return
        }
        if (tabs.size == 1 && tabs[0].isFileTab) {
            val tab = tabs.removeAt(0)
            webViewContainer.removeView(tab.view)
            currentTabIndex = -1
            createNewTab(homeUrl)
            return
        }
        val tab = tabs.removeAt(index)
        webViewContainer.removeView(tab.view)
        tab.webView?.destroy()
        val newIndex = if (index >= tabs.size) tabs.size - 1 else index
        currentTabIndex = -1
        switchToTab(newIndex)
        updateTabCount()
    }

    private fun updateTabCount() {
        btnTabs.text = tabs.size.toString()
    }

    fun currentWebView(): CustomWebView? = if (tabs.isNotEmpty() && currentTabIndex in tabs.indices)
        tabs[currentTabIndex].webView else null

    private fun loadUrl(input: String) {
        hideHistoryDropdown()
        currentWebView()?.loadUrl(normalizeUrl(input))
        hideKeyboard()
        addressBar.clearFocus()
    }

    fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        val q = trimmed.replace(" ", "+")
        val searchUrl = when (searchEngine) {
            "ddg"  -> "https://duckduckgo.com/?q=$q"
            "bing" -> "https://www.bing.com/search?q=$q"
            else   -> "https://www.google.com/search?q=$q"
        }
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> searchUrl
        }
    }

    private fun getDomain(url: String): String {
        return try { URI(url).host ?: "" } catch (e: Exception) { "" }
    }

    private fun updateNavButtons() {
        val wv = currentWebView()
        btnBack.alpha    = if (wv?.canGoBack() == true) 1f else 0.35f
        btnForward.alpha = if (wv?.canGoForward() == true) 1f else 0.35f
    }

    // ── Bottom sheets ─────────────────────────────────────────────────────────

    private fun showTabSwitcher() {
        val dialog = BottomSheetDialog(this)
        val view   = layoutInflater.inflate(R.layout.sheet_tabs, null)
        val recycler  = view.findViewById<RecyclerView>(R.id.tabsRecycler)
        val btnNewTab = view.findViewById<Button>(R.id.btnNewTab)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = TabsAdapter(
            tabs = tabs.toList(),
            currentIndex = currentTabIndex,
            onTabClick = { i -> switchToTab(i); dialog.dismiss() },
            onTabClose = { i -> closeTab(i); dialog.dismiss() }
        )
        btnNewTab.setOnClickListener { createNewTab(); dialog.dismiss() }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun showMenu() {
        val dialog = BottomSheetDialog(this)
        val view   = layoutInflater.inflate(R.layout.sheet_menu, null)

        view.findViewById<LinearLayout>(R.id.menuNewTab).setOnClickListener {
            createNewTab(); dialog.dismiss()
        }
        view.findViewById<LinearLayout>(R.id.menuAddBookmark).setOnClickListener {
            addBookmark(); dialog.dismiss()
        }
        view.findViewById<LinearLayout>(R.id.menuBookmarks).setOnClickListener {
            dialog.dismiss(); showBookmarks()
        }
        view.findViewById<LinearLayout>(R.id.menuHistory).setOnClickListener {
            dialog.dismiss(); showHistory()
        }
        view.findViewById<LinearLayout>(R.id.menuFindInPage).setOnClickListener {
            dialog.dismiss(); showFindBar()
        }
        view.findViewById<LinearLayout>(R.id.menuDesktopMode).setOnClickListener {
            toggleDesktopMode(); dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.tvDesktopMode).text =
            if (isDesktopMode) "Mobile Mode" else "Desktop Mode"

        view.findViewById<LinearLayout>(R.id.menuSharePage).setOnClickListener {
            val url = currentWebView()?.url ?: return@setOnClickListener
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(share, "Share page"))
            dialog.dismiss()
        }
        view.findViewById<LinearLayout>(R.id.menuDownloadVideo).setOnClickListener {
            dialog.dismiss()
            showDownloadSheet()
        }
        view.findViewById<LinearLayout>(R.id.menuSettings).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun showDownloadSheet() {
        val url = currentWebView()?.url ?: run {
            Toast.makeText(this, "No page loaded", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog   = BottomSheetDialog(this)
        val view     = layoutInflater.inflate(R.layout.sheet_download, null)
        val tvTitle  = view.findViewById<TextView>(R.id.tvDlTitle)
        val loading  = view.findViewById<LinearLayout>(R.id.dlLoading)
        val tvStatus = view.findViewById<TextView>(R.id.tvDlStatus)
        val rvFmts   = view.findViewById<RecyclerView>(R.id.rvFormats)
        val tvError  = view.findViewById<TextView>(R.id.tvDlError)
        val btnBest  = view.findViewById<android.widget.Button>(R.id.btnQuickBest)
        val btnAudio = view.findViewById<android.widget.Button>(R.id.btnQuickAudio)
        val quickBtns = view.findViewById<LinearLayout>(R.id.dlQuickButtons)

        tvTitle.text = currentWebView()?.title?.take(60) ?: "Download"
        dialog.setContentView(view)
        dialog.show()

        btnBest.setOnClickListener {
            dialog.dismiss()
            launchDownload(url, null, audioOnly = false)
        }
        btnAudio.setOnClickListener {
            dialog.dismiss()
            launchDownload(url, null, audioOnly = true)
        }

        scope.launch {
            tvStatus.text = "Fetching formats…"
            val formats = withContext(Dispatchers.IO) {
                VideoDownloaderManager.getFormats(this@MainActivity, url)
            }

            loading.visibility = View.GONE
            quickBtns.visibility = View.VISIBLE

            if (formats.isEmpty()) {
                tvError.visibility = View.VISIBLE
                return@launch
            }

            val fmtAdapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<
                    androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

                inner class FH(v: View) :
                    androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
                    val tv: TextView = v.findViewById(R.id.tvFormatLabel)
                }

                override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int) =
                    FH(layoutInflater.inflate(R.layout.item_format, p, false))

                override fun getItemCount() = formats.size

                override fun onBindViewHolder(
                    h: androidx.recyclerview.widget.RecyclerView.ViewHolder, i: Int
                ) {
                    (h as FH).tv.text = formats[i].displayLabel
                    h.itemView.setOnClickListener {
                        dialog.dismiss()
                        launchDownload(url, formats[i], audioOnly = formats[i].isAudioOnly)
                    }
                }
            }

            rvFmts.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            rvFmts.adapter = fmtAdapter
            rvFmts.visibility = View.VISIBLE
        }
    }

    private fun launchDownload(url: String, format: VideoFormat?, audioOnly: Boolean) {
        Toast.makeText(this, "Download started…", Toast.LENGTH_SHORT).show()
        VideoDownloaderManager.startDownload(
            ctx       = this,
            url       = url,
            format    = format,
            audioOnly = audioOnly
        ) { success, _ ->
            if (!success) {
                Toast.makeText(this, "Download failed. Check notification.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showHistory() {
        val allHistory = DetectionLogger.getHistory(this)
        if (allHistory.isEmpty()) {
            Toast.makeText(this, "No history yet", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog    = BottomSheetDialog(this)
        val root      = layoutInflater.inflate(R.layout.sheet_bookmarks, null)
        val recycler  = root.findViewById<RecyclerView>(R.id.bookmarksRecycler)
        val emptyText = root.findViewById<TextView>(R.id.tvEmptyBookmarks)
        emptyText.visibility = View.GONE
        recycler.visibility  = View.VISIBLE
        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = HistoryAdapter(
            allHistory,
            onItemClick  = { url -> loadUrl(url); dialog.dismiss() },
            onDeleteClick = { url ->
                deleteHistoryItem(url)
                // refresh adapter in place
                val updated = DetectionLogger.getHistory(this)
                (recycler.adapter as? HistoryAdapter)?.update(updated)
            }
        )
        recycler.adapter = adapter
        dialog.setContentView(root)
        dialog.show()
    }

    /**
     * Toggles the launcher icon/name between Sam Browser and Chrome
     * by enabling/disabling activity-alias components.
     * No app restart needed — launcher picks up the change within seconds.
     */
    private fun setChromeDisguise(enable: Boolean) {
        val pm = packageManager
        val samComp  = android.content.ComponentName(this, "com.sam.browser.MainActivity")
        val chromeComp = android.content.ComponentName(this, "com.sam.browser.ChromeAlias")

        if (enable) {
            // Hide real icon, show Chrome alias
            pm.setComponentEnabledSetting(
                samComp,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                chromeComp,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
        } else {
            // Restore Sam Browser, hide Chrome alias
            pm.setComponentEnabledSetting(
                chromeComp,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                samComp,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
        }
        Toast.makeText(
            this,
            if (enable) "Now showing as Chrome in launcher" else "Restored as Sam Browser",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showHomepageDialog(parentDialog: com.google.android.material.bottomsheet.BottomSheetDialog) {
        val input = EditText(this).apply {
            setText(homeUrl)
            selectAll()
            setPadding(48, 32, 48, 16)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or
                        android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            hint = "https://..."
            background = null
        }

        val container = android.widget.FrameLayout(this).apply {
            addView(input)
            setBackgroundColor(0xFF2c2c30.toInt())
        }

        androidx.appcompat.app.AlertDialog.Builder(this, R.style.HomepageDialogTheme)
            .setTitle("Set Homepage")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val url = normalizeUrl(input.text.toString().trim())
                homeUrl = url
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString("home_url", url).apply()
                Toast.makeText(this, "Homepage set", Toast.LENGTH_SHORT).show()
                parentDialog.dismiss()
            }
            .setNegativeButton("Reset") { _, _ ->
                homeUrl = HOME_URL
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString("home_url", HOME_URL).apply()
                Toast.makeText(this, "Homepage reset to Google", Toast.LENGTH_SHORT).show()
                parentDialog.dismiss()
            }
            .show()
    }

    private fun showBookmarks() {
        val dialog = BottomSheetDialog(this)
        val view   = layoutInflater.inflate(R.layout.sheet_bookmarks, null)
        val recycler  = view.findViewById<RecyclerView>(R.id.bookmarksRecycler)
        val emptyText = view.findViewById<TextView>(R.id.tvEmptyBookmarks)
        val bookmarks = getBookmarks()
        if (bookmarks.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recycler.visibility  = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recycler.visibility  = View.VISIBLE
            recycler.layoutManager = LinearLayoutManager(this)
            recycler.adapter = BookmarksAdapter(
                bookmarks = bookmarks,
                onBookmarkClick = { url -> loadUrl(url); dialog.dismiss() },
                onBookmarkDelete = { url -> removeBookmark(url); dialog.dismiss() }
            )
        }
        dialog.setContentView(view)
        dialog.show()
    }

    // ── Bookmarks ─────────────────────────────────────────────────────────────

    private fun addBookmark() {
        val wv    = currentWebView() ?: return
        val url   = wv.url ?: return
        val title = wv.title ?: url
        val bookmarks = getBookmarks().toMutableList()
        if (bookmarks.any { it.getString("url") == url }) {
            Toast.makeText(this, "Already bookmarked ★", Toast.LENGTH_SHORT).show()
            return
        }
        bookmarks.add(JSONObject().apply { put("title", title); put("url", url) })
        saveBookmarks(bookmarks)
        Toast.makeText(this, "Bookmark saved ★", Toast.LENGTH_SHORT).show()
    }

    fun getBookmarks(): List<JSONObject> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json  = prefs.getString(BOOKMARKS_KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) { emptyList() }
    }

    private fun removeBookmark(url: String) {
        saveBookmarks(getBookmarks().filter { it.getString("url") != url })
        Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show()
    }

    private fun saveBookmarks(bookmarks: List<JSONObject>) {
        val arr = JSONArray(); bookmarks.forEach { arr.put(it) }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(BOOKMARKS_KEY, arr.toString()).apply()
    }

    // ── Find in page ──────────────────────────────────────────────────────────

    private fun showFindBar() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        showKeyboard(findInput)
    }

    private fun closeFindBar() {
        findBar.visibility = View.GONE
        findInput.text.clear()
        currentWebView()?.clearMatches()
        hideKeyboard()
    }

    // ── Keyboard ──────────────────────────────────────────────────────────────

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    // ── Back press ────────────────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (historyDropdown.visibility == View.VISIBLE) { hideHistoryDropdown(); return }
        if (findBar.visibility == View.VISIBLE) { closeFindBar(); return }
        if (addressBar.isFocused) { addressBar.clearFocus(); hideKeyboard(); return }

        // File tab: close tab and return to previous
        if (tabs.isNotEmpty() && currentTabIndex in tabs.indices && tabs[currentTabIndex].isFileTab) {
            closeTab(currentTabIndex)
            return
        }

        // If hardware back is disabled, swallow the event — don't navigate back
        if (!hardwareBackEnabled) return

        if (currentWebView()?.canGoBack() == true) {
            currentWebView()?.goBack(); showBars(); return
        }
        super.onBackPressed()
    }

    // ── Tab persistence ───────────────────────────────────────────────────────

    private fun saveTabs() {
        val arr = JSONArray()
        for (tab in tabs) {
            val obj = JSONObject().apply {
                put("url", tab.url)
                put("title", tab.title)
                put("isFileTab", tab.isFileTab)
                put("fileType", tab.fileType)
            }
            arr.put(obj)
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(TABS_KEY, arr.toString())
            .putInt("current_tab_index", currentTabIndex)
            .putInt("tab_id_counter", tabIdCounter)
            .apply()
    }

    private fun restoreTabs(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(TABS_KEY, null) ?: return false
        val arr = try { JSONArray(json) } catch (e: Exception) { return false }
        if (arr.length() == 0) return false

        tabIdCounter = prefs.getInt("tab_id_counter", 0)
        val savedIndex = prefs.getInt("current_tab_index", 0)

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val url = obj.getString("url")
            val isFileTab = obj.optBoolean("isFileTab", false)
            val fileType = obj.optString("fileType", "")

            if (isFileTab) {
                val uri = if (url.startsWith("/")) Uri.fromFile(File(url)) else Uri.parse(url)
                when (fileType) {
                    "pdf" -> createPdfTab(uri)
                    "text" -> createTextTab(uri)
                }
            } else {
                createNewTab(url)
            }
        }

        // Switch to the previously active tab
        val targetIndex = savedIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        if (tabs.isNotEmpty()) switchToTab(targetIndex)
        return tabs.isNotEmpty()
    }

    private fun copyToPersistentCache(uri: Uri, prefix: String): String? {
        // Only copy content:// and http(s):// URIs — file:// can be accessed directly
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content" && uri.scheme != "http" && uri.scheme != "https") return null

        return try {
            val dir = File(filesDir, "file_tabs")
            dir.mkdirs()
            val ext = getFileNameFromUri(uri).substringAfterLast('.', "dat")
            val cached = File(dir, "${prefix}_${System.nanoTime()}.$ext")

            when (uri.scheme) {
                "content" -> {
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(cached).use { output -> input.copyTo(output) }
                    }
                }
                "http", "https" -> {
                    URL(uri.toString()).openStream().use { input ->
                        FileOutputStream(cached).use { output -> input.copyTo(output) }
                    }
                }
            }
            cached.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Re-read settings that may have changed in SettingsActivity
        bypassEnabled          = prefs.getBoolean("bypass_enabled", true)
        snifferEnabled         = prefs.getBoolean("sniffer_enabled", true)
        adBlockEnabled         = prefs.getBoolean("adblock_enabled", true)
        hardwareBackEnabled    = prefs.getBoolean("hardware_back_enabled", true)
        forceTextSelectEnabled = prefs.getBoolean("force_text_select", false)
        refreshHoldMs          = prefs.getLong("refresh_hold_ms", 600L)
        homeUrl                = prefs.getString("home_url", HOME_URL) ?: HOME_URL
        chromeDisguiseEnabled  = prefs.getBoolean("chrome_disguise", false)
        searchEngine           = prefs.getString("search_engine", "google") ?: "google"
        val newTextSize        = prefs.getInt("text_size_pct", 100)
        if (newTextSize != textSizePct) {
            textSizePct = newTextSize
            tabs.forEach { it.webView?.settings?.textZoom = textSizePct }
        }
        // Clear cache requested from SettingsActivity
        if (prefs.getBoolean("pending_clear_cache", false)) {
            tabs.forEach { it.webView?.clearCache(true) }
            prefs.edit().remove("pending_clear_cache").apply()
        }
    }

    override fun onPause() {
        super.onPause()
        saveTabs()
    }

    override fun onDestroy() {
        scope.cancel()
        tabs.forEach { it.webView?.destroy() }
        super.onDestroy()
    }
}
