package com.archivebate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Hidden WebView sniffer for archivebate.com. The /watch/<id> page loads its player through Livewire,
 * which injects a MixDrop embed (mixdrop.ag / mxdrop.click /e/<id>) that in turn streams a signed
 * .mp4 from *.mxcontent.net. None of that is in the static HTML, so we load the page like a browser
 * and record the embed url (handed to Cloudstream's MixDrop extractor) and any direct media url.
 */
object ArchivebateBrowser {
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    class Hit(val url: String, val referer: String?, val kind: Kind)
    enum class Kind { EMBED, MEDIA }

    private val segmentExt = listOf(
        ".ts", ".m4s", ".webm", ".mkv", ".aac", ".mp3", ".m4a", ".jpg", ".jpeg", ".png", ".gif", ".webp",
        ".woff", ".woff2", ".ttf", ".css"
    )

    // the player embed that carries the real stream
    private fun isEmbed(u: String): Boolean {
        val l = u.lowercase()
        return Regex("""https?://[a-z0-9.-]*(mixdrop|mxdrop)\.[a-z]+/e/[a-z0-9]+""").containsMatchIn(l)
    }

    // the actual playable media (MixDrop's signed mp4, or any hls master)
    private fun isMedia(u: String): Boolean {
        val l = u.lowercase()
        val path = l.substringBefore("?").substringBefore("#")
        if (path.endsWith(".m3u8")) return true
        return l.contains("mxcontent.net") && path.endsWith(".mp4")
    }

    private val blockedHosts = listOf(
        "google-analytics.com", "googletagmanager.com", "googlesyndication.com", "doubleclick.net",
        "cloudflareinsights.com", "histats.com", "disqus.com", "facebook.net", "hotjar.com",
        "adsco.re", "guvrwavupliw", "laros.world", "adsterra", "popads", "propellerads", "onclick",
        "juicyads", "exoclick", "pubmatic", "rubiconproject", "gumgum", "bidgx", "magsrv"
    )

    private fun isBlocked(u: String): Boolean {
        val host = u.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
        return blockedHosts.any { host.contains(it) }
    }

    private fun isSegment(u: String): Boolean {
        val l = u.lowercase()
        if (isMedia(l) || isEmbed(l)) return false
        val path = l.substringBefore("?").substringBefore("#")
        if (segmentExt.any { path.endsWith(it) }) return true
        return l.contains("/seg-") || l.contains("chunk-stream") || l.contains("init-stream")
    }

    private const val HUSH_JS = """
(function(){
  if (window.__abHush) return; window.__abHush = true;
  try { window.open = function(){ return null; }; } catch(e){}
  function hush(){
    try {
      var m = document.querySelectorAll('video,audio');
      for (var i = 0; i < m.length; i++) { try { m[i].muted = true; m[i].volume = 0; } catch(e){} }
    } catch(e){}
  }
  setInterval(hush, 300);
})();
"""

    private const val TAP_JS = """
(function(){
  try {
    var sel = ['.vjs-big-play-button','.jw-display-icon-container','.plyr__control--overlaid','[aria-label*=Play]','[class*=play]','iframe','video','button'];
    for (var i = 0; i < sel.length; i++) { var el = document.querySelector(sel[i]); if (el) { try { el.click(); } catch(e){} } }
    var v = document.querySelector('video'); if (v) { v.muted = true; try { v.play(); } catch(e){} }
    document.querySelectorAll('iframe').forEach(function(f){ try { f.contentWindow.postMessage('play','*'); } catch(e){} });
  } catch(e){}
})();
"""

    /** Loads [url] and returns the embed/media urls the player requested, best (embed) first. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniff(url: String, referer: String, userAgent: String, timeoutMs: Long): List<Hit> {
        val ctx = appContext ?: return emptyList()
        val hits = CopyOnWriteArrayList<Hit>()
        val pageHost = url.substringAfter("://").substringBefore("/").lowercase()

        val wv: WebView = try {
            withContext(Dispatchers.Main) {
                val w = WebView(ctx)
                w.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = false
                    loadsImagesAutomatically = false
                }
                w.measure(
                    View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
                )
                w.layout(0, 0, 1280, 720)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
                w.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                        view?.evaluateJavascript(HUSH_JS, null)
                    }

                    override fun onPageFinished(view: WebView?, u: String?) {
                        view?.evaluateJavascript(HUSH_JS, null)
                    }

                    // refuse top-level navigation away from the watch page (ad redirects)
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val target = request?.url?.host?.lowercase() ?: return true
                        return request.isForMainFrame && target != pageHost
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val u = request?.url?.toString() ?: return null
                        val ref = request.requestHeaders?.let { it["Referer"] ?: it["referer"] }
                        if (isEmbed(u)) {
                            if (hits.none { it.url == u }) hits.add(Hit(u, ref, Kind.EMBED))
                            return null
                        }
                        if (isMedia(u)) {
                            if (hits.none { it.url == u }) hits.add(Hit(u, ref, Kind.MEDIA))
                            // don't actually download the media
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        if (isSegment(u) || isBlocked(u)) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        return null
                    }
                }
                w.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
                    ): Boolean = false

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        if (newProgress in 5..80) view?.evaluateJavascript(HUSH_JS, null)
                    }
                }
                w.loadUrl(url, mapOf("Referer" to referer))
                w
            }
        } catch (_: Throwable) {
            return emptyList()
        }

        try {
            val start = System.currentTimeMillis()
            var tapped = 0
            var firstHitAt = 0L
            while (System.currentTimeMillis() - start < timeoutMs) {
                delay(300L)
                val elapsed = System.currentTimeMillis() - start
                if (hits.any { it.kind == Kind.MEDIA }) break // a direct media url is all we need
                if (hits.isNotEmpty()) {
                    if (firstHitAt == 0L) firstHitAt = System.currentTimeMillis()
                    if (System.currentTimeMillis() - firstHitAt > 4_000L) break
                } else if ((tapped == 0 && elapsed > 2_500L) || (tapped == 1 && elapsed > 6_000L) ||
                    (tapped == 2 && elapsed > 10_000L)
                ) {
                    tapped++
                    try {
                        withContext(Dispatchers.Main) { wv.evaluateJavascript(TAP_JS, null) }
                    } catch (_: Throwable) {
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                try {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                } catch (_: Throwable) {
                }
            }
        }
        return hits.toList()
    }
}
