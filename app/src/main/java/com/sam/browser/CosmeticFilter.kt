package com.sam.browser

/**
 * Cosmetic ad filter — CSS element hiding.
 *
 * Works alongside AdBlocker (which blocks network requests) to hide ad
 * containers, banners, and overlays that survive network-level blocking
 * because they're rendered inline or via first-party scripts.
 *
 * Mechanism:
 *   Injects a <style> tag with `display: none !important` rules targeting
 *   known ad selectors, plus a MutationObserver that re-applies hiding as
 *   the page inserts new elements dynamically (SPA navigation, lazy ads).
 *
 * Usage in MainActivity (inside onPageFinished):
 *   if (adBlockEnabled) {
 *       view.evaluateJavascript(CosmeticFilter.buildScript(), null)
 *   }
 */
object CosmeticFilter {

    // ── Selector list ─────────────────────────────────────────────────────
    // Format: CSS selector strings. Covers:
    //   - Common ad slot IDs/classes (Google, Taboola, Outbrain, etc.)
    //   - Cookie consent banners
    //   - Newsletter/push notification popups
    //   - Floating "sticky" ad bars
    //   - Indonesian ad networks common on local sites

    private val SELECTORS = listOf(

        // ── Generic ad containers ─────────────────────────────────────────
        "[id*='google_ads']",
        "[id*='GoogleAds']",
        "[class*='google-ads']",
        "[class*='GoogleAds']",
        "[id*='gpt-ad']",
        "[id*='div-gpt-ad']",
        "[class*='gpt-ad']",
        "ins.adsbygoogle",
        ".adsbygoogle",
        "[data-ad-client]",
        "[data-ad-slot]",
        "[data-ad-unit]",
        "[data-google-query-id]",

        // ── Ad wrapper classes (generic) ──────────────────────────────────
        ".ad-wrapper",
        ".ad-container",
        ".ad-banner",
        ".ad-block",
        ".ad-slot",
        ".ad-unit",
        ".ad-zone",
        ".ad-frame",
        ".ad-row",
        ".ad-label",
        "[class^='ad-'][class$='-container']",
        "[id^='ad-'][id$='-container']",
        "[class*='advert']",
        "[id*='advert']",
        "[class*='advertisement']",
        "[id*='advertisement']",
        "[class*='ads-']",
        "[id*='ads-']",
        "[class*='-ads']",
        "[id*='-ads']",
        "[class*='_ads']",
        "[id*='_ads']",
        "[class*='sponsored']",
        "[id*='sponsored']",
        "[class*='promo-banner']",
        "[class*='promo_banner']",
        "[class*='promotional']",

        // ── Taboola ───────────────────────────────────────────────────────
        "[id*='taboola']",
        "[class*='taboola']",
        "[data-widget-id*='taboola']",
        ".trc_rbox_div",
        "#taboola-below-article",
        "#taboola-right-rail",
        "#taboola-mid-article",

        // ── Outbrain ──────────────────────────────────────────────────────
        "[id*='outbrain']",
        "[class*='outbrain']",
        ".OUTBRAIN",
        "[data-widget-id*='outbrain']",

        // ── Criteo ───────────────────────────────────────────────────────
        "[id*='criteo']",
        "[class*='criteo']",
        "[data-criteo]",

        // ── Media.net ─────────────────────────────────────────────────────
        "[id*='medianetstats']",
        "[class*='medianet']",
        "div[id^='mnet']",

        // ── AppNexus / Xandr ──────────────────────────────────────────────
        "[id*='appnexus']",
        "[class*='appnexus']",
        "div[id^='apntag']",

        // ── Popads / popups ───────────────────────────────────────────────
        "[id*='popads']",
        "[class*='popads']",
        ".popup-ad",
        ".popup-banner",
        ".pop-ad",
        "#popup-overlay",
        "[id*='pop-up']",
        "[class*='pop-up']",
        "[id*='popover-ad']",
        "[class*='popover-ad']",

        // ── Sticky / floating banners ─────────────────────────────────────
        ".sticky-ad",
        ".sticky-banner",
        ".sticky-bottom-ad",
        ".sticky-top-ad",
        "#sticky-ad",
        "#sticky-banner",
        "[class*='floating-ad']",
        "[id*='floating-ad']",
        "[class*='fixed-ad']",
        "[class*='fixed-banner']",

        // ── Video ad overlays ─────────────────────────────────────────────
        ".video-ads",
        ".video-ad-overlay",
        ".preroll-ad",
        "[class*='video-ad']",
        "[id*='video-ad']",
        "[id*='player-ad']",
        "[class*='player-ad']",
        ".ytp-ad-module",               // YouTube (won't fully work but hides some UI)
        ".ytp-ad-overlay-container",
        "#player-ads",

        // ── Cookie consent / GDPR banners ────────────────────────────────
        "#cookie-banner",
        "#cookie-consent",
        "#cookie-notice",
        "#cookie-law",
        ".cookie-banner",
        ".cookie-consent",
        ".cookie-notice",
        ".cookie-bar",
        ".cookie-popup",
        "#cookielaw-icon",
        "#cookieConsent",
        ".cookieConsent",
        "#gdpr-banner",
        ".gdpr-banner",
        "#gdpr-consent",
        ".gdpr-consent",
        "#consent-banner",
        ".consent-banner",
        "[id*='cookie'][id*='banner']",
        "[class*='cookie'][class*='banner']",
        "[id*='cookie'][id*='consent']",
        "[class*='cookie'][class*='consent']",
        // OneTrust / CookiePro
        "#onetrust-banner-sdk",
        "#onetrust-consent-sdk",
        ".onetrust-pc-dark-filter",
        "#cookiepro-popup-body",
        // Cookiebot
        "#CybotCookiebotDialog",
        "#CybotCookiebotDialogBody",
        ".CybotCookiebotScrollContainer",
        // ConsentManager
        "[id*='cmplz']",
        "[class*='cmplz']",
        // Quantcast
        ".qc-cmp2-container",
        "[class*='qc-cmp']",

        // ── Push notification prompts ─────────────────────────────────────
        "[class*='push-notification']",
        "[id*='push-notification']",
        "[class*='push-prompt']",
        "[id*='push-prompt']",
        ".pn-overlay",
        "#pn-overlay",
        "[class*='notif-overlay']",
        "[class*='subscribe-overlay']",
        "[class*='subscribe-popup']",
        "[id*='subscribe-popup']",
        "[class*='newsletter-popup']",
        "[id*='newsletter-popup']",

        // ── Email / signup overlays ───────────────────────────────────────
        "[class*='signup-modal']",
        "[class*='signup-overlay']",
        "[class*='email-capture']",
        "[id*='email-capture']",
        "[class*='lead-gen']",
        "[id*='lead-gen']",

        // ── Overlay modals (paywall / ad interstitials) ───────────────────
        ".paywall-overlay",
        "#paywall-overlay",
        "[class*='interstitial']",
        "[id*='interstitial']",
        "[class*='modal-ad']",
        "[id*='modal-ad']",
        "[class*='ad-modal']",
        "[id*='ad-modal']",

        // ── Indonesian ad networks ────────────────────────────────────────
        // PropellerAds
        "[id*='propeller']",
        "[class*='propeller']",
        // Adsterra
        "[id*='adsterra']",
        "[class*='adsterra']",
        // JuicyAds
        "[id*='juicyads']",
        // ExoClick
        "[id*='exoclick']",
        "[class*='exoclick']",
        // Yllix
        "[id*='yllix']"
    )

    // ── Script builder ────────────────────────────────────────────────────

    /**
     * Returns a self-contained JS string that:
     *  1. Injects a <style> element hiding all known ad selectors
     *  2. Attaches a MutationObserver that re-hides newly inserted elements
     *
     * Call via webView.evaluateJavascript(CosmeticFilter.buildScript(), null)
     * in onPageFinished (and optionally onPageStarted for early hiding).
     */
    fun buildScript(): String {
        // Join selectors into a single CSS rule — fewer style rules = faster paint
        val css = SELECTORS.joinToString(",\n") { it } + " { display: none !important; }"

        // JSON-escape for safe embedding in JS template literal
        val cssEscaped = css
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")

        return """
(function() {
    if (window.__samCosmeticApplied) return;
    window.__samCosmeticApplied = true;

    // ── 1. Inject style ───────────────────────────────────────────────────
    function injectStyle() {
        if (document.getElementById('__sam_cosmetic')) return;
        var style = document.createElement('style');
        style.id = '__sam_cosmetic';
        style.textContent = `$cssEscaped`;
        var target = document.head || document.documentElement;
        if (target) target.appendChild(style);
    }
    injectStyle();

    // ── 2. Re-apply on DOM mutations (lazy-loaded ads, SPA navigation) ───
    var observer = new MutationObserver(function(mutations) {
        for (var i = 0; i < mutations.length; i++) {
            var added = mutations[i].addedNodes;
            for (var j = 0; j < added.length; j++) {
                var node = added[j];
                if (node.nodeType !== 1) continue; // element nodes only
                // If the <head> itself was just added (early injection timing),
                // re-inject our style tag into it
                if (node.nodeName === 'HEAD' || node.nodeName === 'BODY') {
                    injectStyle();
                }
            }
        }
    });

    var observeTarget = document.documentElement || document.body;
    if (observeTarget) {
        observer.observe(observeTarget, { childList: true, subtree: true });
    } else {
        // Page not ready yet — wait for DOMContentLoaded
        document.addEventListener('DOMContentLoaded', function() {
            injectStyle();
            observer.observe(document.documentElement, { childList: true, subtree: true });
        }, { once: true });
    }
})();
        """.trimIndent()
    }
}
