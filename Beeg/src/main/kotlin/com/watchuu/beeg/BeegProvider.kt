package com.watchuu.beeg

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * beeg.porn — adult tube behind Cloudflare, built from the captured network log (2026-10-29).
 *
 * LISTINGS (this is what was wrong on the home page)
 *   The site's own config script declares `URL_LATEST = 'new'` and `URL_BEST = 'best'`, and the
 *   capture shows the real paging shape:
 *       /            → /page/2/            (home, mixed sections)
 *       /new/        → /new/2/ …           (latest)
 *       /trends/     → /trends/2/ /trends/3/ …   (capture: /trends/2/, /trends/3/)
 *       /best/       → /best/2/  /best/3/ …      (capture: /best/, /best/2/, /best/3/)
 *   Page 1 has NO "/1/" segment — the old code built /trends/1/ and /best/1/, which the site
 *   does not serve. Cards are `span.item`.
 *
 * CARDS link to two different URL shapes:
 *   /play/5-<video_id>/<slug>/          (from the home / listing pages)
 *   /xid/<video_id>/?e=<encode>         (from the search API)
 *
 * STREAMS (this is why only previews played)
 *   `e` is the **xvideos embed code**. The full video lives on the xvideos player:
 *       https://www.xvideos.com/embedframe/<encode>
 *           html5player.setVideoHLS('https://hls-cdn77.xvideos-cdn.com/…/hls_low.m3u8')
 *           html5player.setVideoUrlHigh('https://mp4-cdn77.xvideos-cdn.com/…/video_360p.mp4?secure=…')
 *           html5player.setVideoUrlLow('https://mp4-cdn77.xvideos-cdn.com/…/video_240p.mp4?secure=…')
 *   The old code fetched https://e.xv-cdn.net/?id=…&e=… and regexed it for setVideoHLS — that
 *   wrapper page only iframes the player, so the regex never matched and the code fell through
 *   to `preview_url` from the metadata API, i.e. a 3-second preview clip.
 *   The metadata API (api-search.xv-cdn.net/id/<id>/) is now used ONLY for metadata, never as a
 *   playable source, and a preview is clearly labelled as such if nothing better is found.
 */
class BeegProvider : MainAPI() {
    override var mainUrl = "https://beeg.porn"
    override var name = "Beeg"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val searchApiBase = "https://api-search.xv-cdn.net"
    private val embedBase = "https://e.xv-cdn.net"
    private val xvideosBase = "https://www.xvideos.com"

    private val ua =
        "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"

    private val cfInterceptor by lazy { CloudflareKiller() }

    // "" is the mixed home page; the other three are the site's own listing paths.
    override val mainPage = mainPageOf(
        "" to "Home",
        "new" to "Latest",
        "trends" to "Trending",
        "best" to "Best"
    )

    // ── Home page ────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val section = request.data.trim('/')
        val url = when {
            section.isEmpty() -> if (page == 1) "$mainUrl/" else "$mainUrl/page/$page/"
            page == 1 -> "$mainUrl/$section/"
            else -> "$mainUrl/$section/$page/"
        }

        val doc = Jsoup.parse(fetchPage(url))
        val items = doc.select("span.item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private suspend fun fetchPage(url: String): String {
        val plain = runCatching { app.get(url, headers = mapOf("User-Agent" to ua)).text }.getOrNull()
        if (!looksBroken(plain)) return plain!!
        return runCatching {
            app.get(url, headers = mapOf("User-Agent" to ua), interceptor = cfInterceptor).text
        }.getOrNull() ?: plain.orEmpty()
    }

    private fun looksBroken(body: String?): Boolean {
        if (body == null || body.length < 600) return true
        val head = body.take(6000)
        return head.contains("Just a moment", ignoreCase = true) ||
            head.contains("challenge-platform") ||
            head.contains("cf_chl_opt") ||
            !head.contains("<html", ignoreCase = true)
    }

    // ── Search ───────────────────────────────────────────────────────────────

    /**
     * Only the long-standing search(query) API is used: the paginated overload returns
     * SearchResponseList, a type missing from older Cloudstream builds, and a provider that
     * declares it cannot be loaded there at all. Two pages are merged instead.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val first = searchPage(query, 1)
        if (first.isEmpty()) return first
        val second = runCatching { searchPage(query, 2) }.getOrNull().orEmpty()
        return (first + second).distinctBy { it.url }
    }

    private suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isBlank()) return emptyList()
        val url = "$searchApiBase/search/?format=ajax&k=$q&p=$page"
        val json = runCatching { app.get(url, headers = mapOf("User-Agent" to ua)).text }.getOrNull()
            ?: return emptyList()
        return parseSearchJson(json)
    }

    /**
     * Each item looks like
     *   {"video_id":89211070,"video_id_encode":"oohutbef54a","title":"…","thumb":"https:\/\/…",
     *    …,"data_hz":{"hz_type":…}}
     * i.e. it contains a nested object, so we slice the payload per item instead of regexing
     * whole objects.
     */
    private fun parseSearchJson(json: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val starts = Regex(""""video_id"\s*:\s*"?(\d+)"?""").findAll(json).toList()
        starts.forEachIndexed { i, m ->
            val end = starts.getOrNull(i + 1)?.range?.first ?: json.length
            val chunk = json.substring(m.range.last, end)
            val id = m.groupValues[1]
            val encode = Regex(""""video_id_encode"\s*:\s*"([^"]+)"""").find(chunk)
                ?.groupValues?.get(1) ?: return@forEachIndexed
            val title = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(chunk)
                ?.groupValues?.get(1)?.jsonUnescape()?.trim() ?: return@forEachIndexed
            val thumb = Regex(""""thumb"\s*:\s*"([^"]+)"""").find(chunk)
                ?.groupValues?.get(1)?.replace("\\/", "/")
            out.add(
                newMovieSearchResponse(title, "$mainUrl/xid/$id/?e=$encode", TvType.NSFW) {
                    this.posterUrl = thumb
                }
            )
        }
        return out.distinctBy { it.url }
    }

    private fun String.jsonUnescape(): String =
        this.replace("\\/", "/").replace("\\\"", "\"").replace("\\u0026", "&")

    // ── Detail ───────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val doc = Jsoup.parse(fetchPage(url))

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: titleFromUrl(url)

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("meta[name=og:image]")?.attr("content")

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val tags = doc.select("[class*=categor] a, a[href*=/category/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }
            .distinct()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    // ── Streams ──────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = Regex("""/xid/(\d+)/""").find(data)?.groupValues?.get(1)
            ?: Regex("""/play/\d+-(\d+)/""").find(data)?.groupValues?.get(1)
        var encode = Regex("""[?&]e=([A-Za-z0-9]+)""").find(data)?.groupValues?.get(1)

        // 1. We already know the xvideos embed code (search results) — go straight to the player.
        if (encode != null && playFromEmbedframe(encode, data, callback)) return true

        // 2. Otherwise look for the embed code on the page itself.
        var pageHtml: String? = null
        if (encode == null) {
            pageHtml = runCatching { fetchPage(data) }.getOrNull()
            if (pageHtml != null) encode = findEncode(pageHtml)
        }

        if (encode != null) {
            // 2a. through beeg's own wrapper, which iframes the player (and carries a CDN token)
            if (videoId != null && playFromWrapper(videoId, encode, data, callback)) return true
            // 2b. plain player page again (cookie may have been refreshed by the wrapper call)
            if (playFromEmbedframe(encode, data, callback)) return true
        }

        // 3. /play/ pages carry no embed code — resolve it through the site's own search API.
        if (videoId != null) {
            val resolved = findEncodeViaSearch(videoId, pageHtml, data)
            if (resolved != null && playFromEmbedframe(resolved, data, callback)) return true
            if (resolved != null) playFromWrapper(videoId, resolved, data, callback)
        }

        // 4. Nothing playable: offer the preview, but say so instead of pretending it is the video.
        val preview = videoId?.let { id ->
            runCatching {
                app.get("$searchApiBase/id/$id/?format=ajax&e=${encode ?: ""}").text
            }.getOrNull()
        }?.let { Regex(""""preview_url"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
            ?.replace("\\/", "/")

        if (!preview.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(name, "$name (preview only)", preview, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
        return false
    }

    /** https://www.xvideos.com/embedframe/<encode> — the real player page. */
    private suspend fun playFromEmbedframe(
        encode: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching {
            app.get(
                "$xvideosBase/embedframe/$encode",
                headers = mapOf("User-Agent" to ua),
                referer = referer
            ).text
        }.getOrNull() ?: return false
        return emitPlayerSources(html, callback)
    }

    /** https://e.xv-cdn.net/?id=…&e=… → iframe to the proxied player → same config. */
    private suspend fun playFromWrapper(
        videoId: String,
        encode: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val wrapper = runCatching {
            app.get(
                "$embedBase/?id=$videoId&e=$encode",
                headers = mapOf("User-Agent" to ua),
                referer = referer,
                interceptor = cfInterceptor
            ).text
        }.getOrNull() ?: return false

        val playerUrl = Regex("""<iframe[^>]+src=["']([^"']*embedframe[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(wrapper)?.groupValues?.get(1)?.replace("&amp;", "&")
            ?: Regex("""(https?://[^"'\s<>]*embedframe/[^"'\s<>]+)""")
                .find(wrapper)?.groupValues?.get(1)?.replace("&amp;", "&")

        if (playerUrl != null) {
            val html = runCatching {
                app.get(
                    if (playerUrl.startsWith("http")) playerUrl else "$xvideosBase/embedframe/$encode",
                    headers = mapOf("User-Agent" to ua),
                    referer = referer
                ).text
            }.getOrNull()
            if (html != null && emitPlayerSources(html, callback)) return true
        }

        // The wrapper page itself sometimes carries the config inline.
        return emitPlayerSources(wrapper, callback)
    }

    /** Reads html5player.setVideoHLS / setVideoUrlHigh / setVideoUrlLow out of the player page. */
    private suspend fun emitPlayerSources(html: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (html.isBlank()) return false
        val referer = "$xvideosBase/"

        val hls = Regex("""setVideoHLS\(['"]([^'"]+)['"]\)""").find(html)?.groupValues?.get(1)
        val high = Regex("""setVideoUrlHigh\(['"]([^'"]+)['"]\)""").find(html)?.groupValues?.get(1)
        val low = Regex("""setVideoUrlLow\(['"]([^'"]+)['"]\)""").find(html)?.groupValues?.get(1)

        var found = false
        if (!hls.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(name, "$name (HLS)", hls, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Referer" to referer, "User-Agent" to ua)
                }
            )
            found = true
        }
        if (!high.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(name, "$name (MP4 360p)", high, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = Qualities.P360.value
                    this.headers = mapOf("Referer" to referer, "User-Agent" to ua)
                }
            )
            found = true
        }
        if (!low.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(name, "$name (MP4 240p)", low, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = Qualities.P240.value
                    this.headers = mapOf("Referer" to referer, "User-Agent" to ua)
                }
            )
            found = true
        }
        return found
    }

    private fun findEncode(html: String): String? =
        Regex("""embedframe/([A-Za-z0-9]{6,20})""").find(html)?.groupValues?.get(1)
            ?: Regex("""[?&]e=([A-Za-z0-9]{8,14})""").find(html)?.groupValues?.get(1)
            ?: Regex(""""video_id_encode"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)

    /**
     * /play/ URLs only carry the numeric id. The site's search API returns `video_id_encode`
     * for every hit, so search by the video title and pick the row with the same video_id.
     */
    private suspend fun findEncodeViaSearch(videoId: String, pageHtml: String?, data: String): String? {
        val title = pageHtml?.let { Jsoup.parse(it).selectFirst("h1")?.text() }?.trim()
            ?.ifBlank { null }
            ?: titleFromUrl(data)

        val words = title.split(Regex("\\s+"))
            .filter { it.length > 3 }
            .take(4)
            .joinToString(" ")
            .ifBlank { return null }

        val q = URLEncoder.encode(words, "UTF-8").replace("+", "%20")
        val json = runCatching {
            app.get("$searchApiBase/search/?format=ajax&k=$q&p=1", headers = mapOf("User-Agent" to ua)).text
        }.getOrNull() ?: return null

        // Items contain nested objects, so read a window after the matching video_id instead.
        val hit = Regex(""""video_id"\s*:\s*"?$videoId"?\s*,""").find(json) ?: return null
        val chunk = json.substring(hit.range.last, minOf(json.length, hit.range.last + 400))
        return Regex(""""video_id_encode"\s*:\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1)
    }

    // ── Cards ────────────────────────────────────────────────────────────────

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript")) return null

        val url = if (href.startsWith("http")) href else fixUrl(href)
        // Only video pages: /play/5-<id>/<slug>/ or /xid/<id>/?e=…
        if (!url.contains("/play/") && !url.contains("/xid/")) return null

        val title = sequenceOf(
            a.attr("title").trim(),
            selectFirst(".title")?.text()?.trim(),
            selectFirst("span.title")?.text()?.trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            titleFromUrl(url)
        ).firstOrNull { !it.isNullOrBlank() } ?: return null

        val img = selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        return newMovieSearchResponse(title.trim(), url, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    /** /play/5-6623078/gina-gerson/ → "gina gerson" */
    private fun titleFromUrl(url: String): String {
        val slug = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        val cleaned = slug.replace(Regex("^\\d+-"), "")
        return cleaned.replace("-", " ").trim().ifBlank { "Video" }
    }
}
