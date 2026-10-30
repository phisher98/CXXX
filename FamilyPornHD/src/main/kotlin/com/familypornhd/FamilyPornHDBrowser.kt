package com.familypornhd

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * familypornhd.com sits behind Cloudflare ("Just a moment..." / Turnstile). Same bypass design as
 * the other Watchuu extensions (NartoDrama/Vivarium), chosen in the extension settings:
 *  1. plain HTTP with the saved cf_clearance cookie + the exact same user agent (fast path)
 *  2. Cloudstream's own CloudflareKiller
 *  3. a hidden WebView that loads the site like a browser
 *  4. a VISIBLE solver window: the real site full-screen, the user ticks the Turnstile box once,
 *     it closes by itself as soon as the cf_clearance cookie appears
 *  5. a manual cookie pasted in the settings
 */
object FamilyPornHDBrowser {
    private const val IDLE_CLOSE_MS = 3 * 60_000L
    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private var appContext: Context? = null
    private var webView: WebView? = null
    private val lock = Mutex()
    private val solveLock = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val killer by lazy { CloudflareKiller() }

    @Volatile private var lastUse = 0L
    @Volatile private var defaultUa: String? = null

    @Volatile
    var lastTrace = ""
        private set

    @Volatile
    var lastMethod = "-"
        private set

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        FamilyPornHDSettings.init(context)
    }

    private fun site() = FamilyPornHDSettings.siteUrl
    private fun home() = site() + "/"

    // ------------------------------------------------------------------ user agent + cookies

    /** One user agent for everything - cf_clearance is bound to it. */
    fun userAgent(): String {
        val custom = FamilyPornHDSettings.customUa
        var ua = custom.ifBlank {
            defaultUa ?: (try {
                appContext?.let { WebSettings.getDefaultUserAgent(it) }
            } catch (_: Throwable) {
                null
            } ?: FALLBACK_UA).also { defaultUa = it }
        }
        if (custom.isBlank() && FamilyPornHDSettings.stripWv) {
            ua = ua.replace("; wv)", ")").replace(" Version/4.0", "")
        }
        return ua
    }

    private fun applyManualCookie() {
        val raw = FamilyPornHDSettings.manualCookie
        if (raw.isBlank()) return
        try {
            val cm = CookieManager.getInstance()
            raw.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
                cm.setCookie(site(), "$it; path=/; secure")
            }
            cm.flush()
        } catch (_: Throwable) {
        }
    }

    fun cookies(): String? {
        applyManualCookie()
        val fromWv = try {
            CookieManager.getInstance().getCookie(site())
        } catch (_: Throwable) {
            null
        }
        val manual = FamilyPornHDSettings.manualCookie
        return listOfNotNull(fromWv?.ifBlank { null }, manual.ifBlank { null }).joinToString("; ").ifBlank { null }
    }

    fun hasClearance(): Boolean = cookies()?.contains("cf_clearance") == true

    fun posterHeaders(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = userAgent()
        h["Referer"] = home()
        cookies()?.takeIf { it.isNotBlank() }?.let { h["Cookie"] = it }
        return h
    }

    fun isChallenge(code: Int, body: String): Boolean {
        val head = body.take(4000)
        return code == 403 || code == 503 || code == 429 ||
            head.contains("<title>Just a moment", true) ||
            head.contains("Performing security verification", true) ||
            head.contains("Checking your browser", true) ||
            (head.contains("challenge-platform") && head.contains("cf_chl", true))
    }

    // ------------------------------------------------------------------ main entry

    /** GET a familypornhd url as text, going through Cloudflare the way the settings say. */
    suspend fun get(url: String, referer: String): String {
        val mode = FamilyPornHDSettings.mode
        val trace = StringBuilder()
        fun done(method: String, body: String): String {
            lastMethod = method
            trace.append("-> ok via ").append(method)
            lastTrace = trace.toString()
            return body
        }
        direct(url, referer, trace)?.let { return done("cookie (fast)", it) }

        when (mode) {
            FamilyPornHDSettings.Mode.COOKIE -> {}
            FamilyPornHDSettings.Mode.CLOUDSTREAM -> viaKiller(url, referer, trace)?.let { return done("Cloudstream bypass", it) }
            FamilyPornHDSettings.Mode.AUTO, FamilyPornHDSettings.Mode.HIDDEN ->
                viaHidden(url, trace)?.let { return done("hidden browser", it) }
            FamilyPornHDSettings.Mode.SOLVER -> {}
        }

        val prompt = mode == FamilyPornHDSettings.Mode.SOLVER ||
            (FamilyPornHDSettings.autoPrompt && (mode == FamilyPornHDSettings.Mode.AUTO || mode == FamilyPornHDSettings.Mode.CLOUDSTREAM))
        if (prompt) {
            trace.append("solver window; ")
            if (solveInteractive(null)) {
                direct(url, referer, trace)?.let { return done("solver + cookie", it) }
                viaHidden(url, trace)?.let { return done("solver + hidden browser", it) }
            } else trace.append("not solved; ")
        }
        lastTrace = trace.toString()
        throw ErrorLoadingException(
            if (mode == FamilyPornHDSettings.Mode.COOKIE)
                "FamilyPornHD: the manual cookie was rejected. Paste a fresh cf_clearance + matching user agent in the extension settings."
            else "FamilyPornHD: Cloudflare not passed. Open Extensions > FamilyPornHD > settings and tap \"Solve Cloudflare now\"."
        )
    }

    private suspend fun direct(url: String, referer: String, trace: StringBuilder): String? {
        val cookie = cookies()
        return try {
            val h = HashMap<String, String>()
            h["User-Agent"] = userAgent()
            h["Referer"] = referer
            h["Accept-Language"] = "en-US,en;q=0.9"
            h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            if (!cookie.isNullOrBlank()) h["Cookie"] = cookie
            val r = app.get(url, headers = h, timeout = 20L)
            val body = r.text
            if (r.code in 200..299 && !isChallenge(r.code, body)) body
            else {
                trace.append("direct HTTP ").append(r.code).append(if (isChallenge(r.code, body)) " (challenge)" else "").append("; ")
                null
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            trace.append("direct error ").append(e.javaClass.simpleName).append("; ")
            null
        }
    }

    private suspend fun viaKiller(url: String, referer: String, trace: StringBuilder): String? = try {
        val r = app.get(url, headers = mapOf("Referer" to referer), interceptor = killer, timeout = 60L)
        val body = r.text
        if (r.code in 200..299 && !isChallenge(r.code, body)) body
        else {
            trace.append("cloudstream HTTP ").append(r.code).append("; "); null
        }
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        trace.append("cloudstream error ").append(e.javaClass.simpleName).append("; ")
        null
    }

    private suspend fun viaHidden(url: String, trace: StringBuilder): String? {
        if (!ensure()) {
            trace.append("hidden browser did not pass in ").append(FamilyPornHDSettings.timeoutSec).append("s; ")
            return null
        }
        // once the hidden browser is cleared, the plain request carries the WebView's cf_clearance
        return direct(url, home(), trace)
    }

    suspend fun test(): String {
        val start = System.currentTimeMillis()
        return try {
            val body = get(site() + "/?s=step", home())
            val cards = Regex(""""https://[^"]*familypornhd\.com/[a-z0-9-]+/"""").findAll(body).count()
            "OK via $lastMethod in ${(System.currentTimeMillis() - start) / 1000}s\n$cards results found when searching \"step\".\n\nCookie saved: ${hasClearance()}\nUser agent:\n${userAgent()}"
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            "FAILED: ${e.message}\n\nMode: ${FamilyPornHDSettings.mode.label}\nCookie saved: ${hasClearance()}\nUser agent:\n${userAgent()}\n\nTry \"Solve Cloudflare now\", or switch mode."
        }
    }

    suspend fun reset(clearCookies: Boolean, uaChanged: Boolean = false) {
        withContext(Dispatchers.Main) {
            destroyHidden()
            if (clearCookies || uaChanged) {
                try {
                    val cm = CookieManager.getInstance()
                    cm.getCookie(site())?.split(";")?.map { it.substringBefore("=").trim() }?.forEach { name ->
                        cm.setCookie(site(), "$name=; Max-Age=0; path=/")
                        cm.setCookie(site(), "$name=; Max-Age=0; path=/; domain=.${site().substringAfter("://")}")
                    }
                    cm.flush()
                } catch (_: Throwable) {
                }
            }
        }
        lastMethod = "-"
    }

    // ------------------------------------------------------------------ shared WebView setup

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView, light: Boolean) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = !light
            blockNetworkImage = light
            userAgentString = userAgent()
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
    }

    private fun loadSite(wv: WebView) = wv.loadUrl(home(), mapOf("X-Requested-With" to ""))

    // ------------------------------------------------------------------ hidden browser

    private fun createHidden(ctx: Context): WebView {
        applyManualCookie()
        val wv = WebView(ctx)
        configure(wv, FamilyPornHDSettings.lightweight)
        val dm = ctx.resources.displayMetrics
        wv.layout(0, 0, dm.widthPixels.coerceAtLeast(360), dm.heightPixels.coerceAtLeast(640))
        wv.webViewClient = WebViewClient()
        loadSite(wv)
        return wv
    }

    private fun destroyHidden() {
        val wv = webView ?: return
        webView = null
        try {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        } catch (_: Throwable) {
        }
    }

    private suspend fun eval(wv: WebView?, js: String): String? = withContext(Dispatchers.Main) {
        if (wv == null) return@withContext null
        val d = CompletableDeferred<String?>()
        try {
            wv.evaluateJavascript(js) { d.complete(it) }
        } catch (_: Throwable) {
            d.complete(null)
        }
        withTimeoutOrNull(5_000L) { d.await() }
    }

    private const val READY_JS =
        "(function(){try{return !/just a moment|attention required|security verification|un instant|einen moment/i.test(document.title)&&" +
            "document.readyState!=='loading'&&!document.querySelector('#challenge-form,#cf-challenge-running,#challenge-stage')&&" +
            "!/Checking your browser/i.test(document.body?document.body.innerText.slice(0,300):'')&&" +
            "!!document.querySelector('article,.g1-collection,.entry-title,meta[name=\"generator\"]')}catch(e){return false}})()"

    private suspend fun pageReady(wv: WebView?): Boolean = eval(wv, READY_JS) == "true"

    /** Opens (or reuses) the hidden page and waits until Cloudflare lets it through. */
    suspend fun ensure(timeoutMs: Long = FamilyPornHDSettings.timeoutSec * 1000L): Boolean = lock.withLock {
        val ctx = appContext ?: return@withLock false
        lastUse = System.currentTimeMillis()
        if (webView == null) {
            withContext(Dispatchers.Main) { webView = createHidden(ctx) }
            scheduleIdleClose()
        }
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (pageReady(webView)) {
                try {
                    CookieManager.getInstance().flush()
                } catch (_: Throwable) {
                }
                return@withLock true
            }
            delay(700L)
        }
        withContext(Dispatchers.Main) { destroyHidden() }
        false
    }

    private fun scheduleIdleClose() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (webView == null) return
                if (System.currentTimeMillis() - lastUse >= IDLE_CLOSE_MS) destroyHidden()
                else main.postDelayed(this, 30_000L)
            }
        }, 30_000L)
    }

    // ------------------------------------------------------------------ visible solver window

    @Volatile private var solving: CompletableDeferred<Boolean>? = null
    @Volatile private var lastSolvedAt = 0L

    /**
     * Shows the real site full-screen so the user can tick the Cloudflare box.
     * Closes by itself once the page is through and a cf_clearance cookie exists.
     */
    suspend fun solveInteractive(activity: Activity?): Boolean {
        solving?.let { return it.await() }
        val result = solveLock.withLock {
            solving?.let { return@withLock it.await() }
            if (System.currentTimeMillis() - lastSolvedAt < 15_000L) return@withLock true
            val act = activity ?: FamilyPornHDSettings.currentActivity() ?: return@withLock false
            val d = CompletableDeferred<Boolean>()
            solving = d
            withContext(Dispatchers.Main) { openSolver(act, d) }
            val ok = withTimeoutOrNull(5 * 60_000L) { d.await() } ?: false
            solving = null
            ok
        }
        if (result) {
            lastSolvedAt = System.currentTimeMillis()
            withContext(Dispatchers.Main) { destroyHidden() }
            lastMethod = "-"
            FamilyPornHDSettings.solvedUa = userAgent()
        }
        return result
    }

    @SuppressLint("SetTextI18n")
    private fun openSolver(act: Activity, done: CompletableDeferred<Boolean>) {
        if (act.isFinishing) {
            done.complete(false); return
        }
        applyManualCookie()
        val density = act.resources.displayMetrics.density
        val dialog = Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
            setBackgroundColor(Color.rgb(30, 30, 30))
        }
        val info = TextView(act).apply {
            text = "FamilyPornHD: tick \"Verify you are human\" below (if shown). This window closes by itself when done."
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = Button(act).apply {
            text = "Close"
            isAllCaps = false
        }
        bar.addView(info)
        bar.addView(close)

        val wv = WebView(act)
        configure(wv, light = false)
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(root)

        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            try {
                CookieManager.getInstance().flush()
            } catch (_: Throwable) {
            }
            try {
                wv.stopLoading(); wv.destroy()
            } catch (_: Throwable) {
            }
            try {
                if (dialog.isShowing) dialog.dismiss()
            } catch (_: Throwable) {
            }
            done.complete(ok)
            FamilyPornHDSettings.toast(if (ok) "FamilyPornHD: Cloudflare passed" else "FamilyPornHD: Cloudflare not solved")
        }

        close.setOnClickListener { finish(hasClearance()) }
        dialog.setOnDismissListener { finish(hasClearance()) }

        var readyCount = 0
        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                try {
                    wv.evaluateJavascript(READY_JS) { r ->
                        readyCount = if (r == "true") readyCount + 1 else 0
                        if (r == "true" && (hasClearance() || readyCount >= 4)) {
                            info.text = "Passed - closing..."
                            main.postDelayed({ finish(true) }, 800L)
                        } else if (!finished) main.postDelayed(this, 1000L)
                    }
                } catch (_: Throwable) {
                    if (!finished) main.postDelayed(this, 1000L)
                }
            }
        }
        try {
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            loadSite(wv)
            main.postDelayed(poll, 2000L)
        } catch (_: Throwable) {
            finish(false)
        }
    }
}
