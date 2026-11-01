package com.watchuu.youperv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * youperv.com — built from the capture of 2026-11-01 05:34.
 *
 * A DLE site (its search form posts do=search&subaction=search&story=…), so:
 *
 *   listing   a.item-link[href] > div.item-in > img.poster[src]   (19 per page)
 *   paging    path: /page/N/   - confirmed in the capture at /page/2/ and /page/3/
 *   search    POST / with do=search, subaction=search, story=<query>
 *   detail    /{category}/{id}-{slug}.html
 *   video     a plain html5 player, no embed and no obfuscation:
 *               <video …><source type="video/mp4" src="https://files.klubnichka-hd.com/…mp4">
 *             served from that file host and expecting youperv.com as Referer.
 */
class YoupervProvider : MainAPI() {
    override var mainUrl = "https://youperv.com"
    override var name = "Youperv"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "anal" to "Anal",
        "big-dick" to "Big Dick",
        "amateur" to "Amateur"
    )

    private fun userAgent(): String = UA

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): String? =
        runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgent(),
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Referer" to referer
                ),
                timeout = 25L
            ).text
        }.getOrNull()?.takeIf { it.contains("<html", true) || it.contains("item-in") }

    private fun pageUrl(path: String, page: Int): String {
        val base = "$mainUrl/" + path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        return if (page > 1) "${base}page/$page/" else base
    }

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val body = fetch(pageUrl(request.data, page))
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = cards(Jsoup.parse(body))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    /** DLE search is a POST form; sending the same fields is what the site itself does. */
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val first = runCatching {
            app.post(
                "$mainUrl/",
                data = mapOf(
                    "do" to "search",
                    "subaction" to "search",
                    "story" to q
                ),
                headers = mapOf(
                    "User-Agent" to userAgent(),
                    "Referer" to "$mainUrl/",
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                timeout = 25L
            ).text
        }.getOrNull() ?: return emptyList()

        val items = cards(Jsoup.parse(first))
        if (items.isEmpty()) {
            // some DLE installs answer the GET form instead
            val alt = fetch("$mainUrl/?do=search&subaction=search&story=${URLEncoder.encode(q, "UTF-8")}")
            return alt?.let { cards(Jsoup.parse(it)) }.orEmpty()
        }
        return items
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("a.item-link, div.item-in")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        // the anchor may be the element itself or the thing that wraps it
        val a = (if (tagName() == "a") this else selectFirst("a[href]")) ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank() || !href.contains(".html")) return null
        if (href.contains("/page/") || href.contains("?")) return null

        val img = selectFirst("img.poster, img[src]") ?: a.selectFirst("img[src]")
        val title = selectFirst(".item-title, h2")?.text()?.trim()?.ifBlank { null }
            ?: img?.attr("alt")?.trim()?.ifBlank { null }
            ?: img?.attr("title")?.trim()?.ifBlank { null }
            ?: a.attr("title").trim().ifBlank { null }
            ?: return null

        val poster = img?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val html = fetch(url) ?: return null
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("video[poster]")?.attr("poster")?.ifBlank { null }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        val tags = doc.select("a[href*=/tag/], .item-tags a").map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }.distinct().take(12)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    // ── Streams ────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = fetch(data) ?: return false
        val doc = Jsoup.parse(html)

        val candidates = LinkedHashSet<String>()

        // the html5 player's own sources
        doc.select("video source[src], video[src]").forEach { el ->
            val src = el.attr("src").ifBlank { el.attr("data-src") }.trim()
            if (src.startsWith("http")) candidates.add(src)
        }

        // anything else that is literally a file in the page
        Regex("""https?://[^\s"'<>\\]+\.(?:mp4|m3u8|webm)[^\s"'<>\\]*""")
            .findAll(html).map { it.value.replace("&amp;", "&") }
            .filter { !isAdHost(it) }
            .forEach { candidates.add(it) }

        val links = candidates.filter { it.startsWith("http") && !isAdHost(it) }.distinct()
        if (links.isEmpty()) return false

        links.forEachIndexed { index, url ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = if (index == 0) name else "$name (${index + 1})",
                    url = url,
                    type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to userAgent()
                    )
                }
            )
        }
        return true
    }

    private fun isAdHost(url: String): Boolean {
        val u = url.lowercase()
        return listOf(
            "fluidplayer.com", "doubleclick", "googlesyndication", "exoclick",
            "juicyads", "popads", "onclickads", "addtoany", "cloudflareinsights"
        ).any { u.contains(it) }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
