package com.sam.browser

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Lightweight ad/tracker blocker.
 * Uses a HashSet for O(1) domain lookup — zero regex, minimal overhead.
 * Returns an empty 200 response for blocked domains so the page doesn't hang
 * waiting for a connection timeout.
 */
object AdBlocker {

    private val BLOCKED_DOMAINS = hashSetOf(
        // ── Google advertising/tracking ────────────────────────────────────
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "googlesyndication.com",
        "googleadservices.com",
        "doubleclick.net",
        "adservice.google.com",
        "adservice.google.co.id",
        "pagead2.googlesyndication.com",
        "stats.g.doubleclick.net",
        "cm.g.doubleclick.net",
        "tpc.googlesyndication.com",

        // ── Facebook / Meta ───────────────────────────────────────────────
        "connect.facebook.net",
        "facebook.net",
        "graph.facebook.com",
        "an.facebook.com",
        "staticxx.facebook.com",

        // ── Analytics platforms ───────────────────────────────────────────
        "hotjar.com",
        "static.hotjar.com",
        "script.hotjar.com",
        "mixpanel.com",
        "api.mixpanel.com",
        "cdn.mxpnl.com",
        "segment.com",
        "api.segment.io",
        "cdn.segment.com",
        "amplitude.com",
        "api.amplitude.com",
        "cdn.amplitude.com",
        "heap.io",
        "heapanalytics.com",
        "cdn.heapanalytics.com",
        "fullstory.com",
        "rs.fullstory.com",
        "edge.fullstory.com",
        "logrocket.com",
        "cdn.logrocket.com",
        "r.logrocket.io",
        "sentry.io",            // error tracking (optional, comment out if you use it)
        "browser.sentry-cdn.com",

        // ── Ad networks ───────────────────────────────────────────────────
        "ads.yahoo.com",
        "media.net",
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "casalemedia.com",
        "indexexchange.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "cdn.taboola.com",
        "trc.taboola.com",
        "outbrain.com",
        "widgets.outbrain.com",
        "adsrvr.org",
        "serving-sys.com",
        "smartadserver.com",
        "appnexus.com",
        "yieldmo.com",
        "liveintent.com",

        // ── Tracking / fingerprinting ─────────────────────────────────────
        "scorecardresearch.com",
        "quantserve.com",
        "quantcount.com",
        "chartbeat.com",
        "static.chartbeat.com",
        "ping.chartbeat.net",
        "newrelic.com",
        "bam.nr-data.net",
        "js-agent.newrelic.com",
        "nr-data.net",
        "optimizely.com",
        "cdn.optimizely.com",
        "p13n.optimizely.com",
        "crazyegg.com",
        "script.crazyegg.com",
        "mouseflow.com",
        "cdn.mouseflow.com",
        "clarity.ms",                   // Microsoft Clarity
        "c.clarity.ms",
        "bat.bing.com",                 // Bing ads/tracking
        "bat.r.msn.com",
        "snap.licdn.com",               // LinkedIn Insight
        "analytics.tiktok.com",
        "log.byteoversea.com",
        "ads-twitter.com",
        "ads.twitter.com",
        "syndication.twitter.com",
        "platform.twitter.com",
        "t.co",
        "pin.it",
        "ct.pinterest.com",

        // ── Telemetry / beacon ─────────────────────────────────────────────
        "beacon.krxd.net",
        "d.turn.com",
        "userzoom.com",
        "survey.medallia.com",
        "kampyle.com",
        "qualtrics.com",
        "sstats.adobe.com",
        "omtrdc.net",
        "demdex.net",
        "everesttech.net",
        "2o7.net",

        // ── Generic tracker subdomains ────────────────────────────────────
        "pixel.facebook.com",
        "pixel.wp.com",
        "pixel.quantserve.com",
        "sp.analytics.yahoo.com",
        "mc.yandex.ru",
        "mc.yandex.com",
        "counter.ok.ru",
        "top-fwz1.mail.ru",
        "top.mail.ru",
        "informer.mail.ru",

        // ── Cookie consent / pop-up noise ─────────────────────────────────
        "cdn.cookielaw.org",
        "optanon.blob.core.windows.net",
        "cookiepro.com",
        "app.usercentrics.eu",
        "consentmanager.net",
        "consent.cookiebot.com",
        "cdn.cookiebot.com"
    )

    private val EMPTY_RESPONSE = WebResourceResponse(
        "text/plain", "utf-8", 200, "OK",
        mapOf("Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(ByteArray(0))
    )

    /**
     * Call this from shouldInterceptRequest.
     * Returns a blank response if the URL's host matches the blocklist, null otherwise.
     */
    fun shouldBlock(url: String): WebResourceResponse? {
        val host = extractHost(url) ?: return null
        return if (isBlocked(host)) EMPTY_RESPONSE else null
    }

    private fun extractHost(url: String): String? {
        return try {
            // Fast path: skip data/blob URIs
            if (url.startsWith("data:") || url.startsWith("blob:")) return null
            val start = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return null
            val end = url.indexOf('/', start).let { if (it < 0) url.length else it }
            // Strip port
            val hostWithPort = url.substring(start, end).lowercase()
            hostWithPort.substringBefore(':')
        } catch (e: Exception) { null }
    }

    private fun isBlocked(host: String): Boolean {
        if (BLOCKED_DOMAINS.contains(host)) return true
        // Check parent domain (e.g. "cdn.google-analytics.com" → "google-analytics.com")
        val dot = host.indexOf('.')
        if (dot >= 0) {
            val parent = host.substring(dot + 1)
            if (BLOCKED_DOMAINS.contains(parent)) return true
        }
        return false
    }
}
