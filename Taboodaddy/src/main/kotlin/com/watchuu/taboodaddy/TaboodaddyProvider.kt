package com.watchuu.taboodaddy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * taboodaddy.com — built from the capture of 2026-10-31 23:08.
 *
 * A WordPress tube that answers a plain client directly (the capture's category page is a 200
 * with no challenge), so there is no browser machinery here on purpose.
 *
 *   listing   div.video-block[data-post-id] > a.thumb[href], img[data-src], .title
 *   paging    path: /page/N/
 *   detail    /{slug}_{id} or /{slug}
 *   video     the page carries it in a meta tag, which is the whole trick:
 *               <meta itemprop="contentUrl" content="https://video.twimg.com/amplify_video/…/pl/….m3u8">
 *             with thumbnailUrl and duration alongside it. The videos are hosted by Twitter's CDN,
 *             so what plays is that HLS master, not anything the page's own player builds.
 *   player    an iframe to /wp-content/plugins/clean-tube-player/public/player-x.php?q=<base64>
 *             which is only a wrapper around the same url - kept as a fallback for the odd post
 *             that has no contentUrl.
 */
class TaboodaddyProvider : MainAPI() {
    override var mainUrl = "https://taboodaddy.com"
    override var name = "Taboodaddy"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/taboo" to "Taboo",
        "category/brazzers" to "Brazzers",
        "category/bangbros" to "BangBros",
        "category/naughtyamerica" to "Naughty America",
        "category/realitykings" to "Reality Kings",
        "category/mom-son-porn" to "Mom & Son",
        "category/brother-sister-porn" to "Brother & Sister"
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
        }.getOrNull()?.takeIf { it.contains("<html", true) }

    // ── Listings ───────────────────────────────────────────────────────────────

    private fun pageUrl(path: String, page: Int): String {
        val base = "$mainUrl/" + path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        return if (page > 1) "${base}page/$page/" else base
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = fetch(pageUrl(request.data, page))?.let { Jsoup.parse(it) }
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = cards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isBlank()) return emptyList()

        val first = fetch("$mainUrl/?s=$q")?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        if (first.isEmpty()) return first
        val second = fetch("$mainUrl/page/2/?s=$q")?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        return (first + second).distinctBy { it.url }
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("div.video-block, div.thumb-block, article.loop-video")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a.thumb[href], a[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank()) return null
        if (href.contains("/category/") || href.contains("/page/") || href.contains("/tag/")) return null
        if (href.contains("?s=") || href.contains("/feed")) return null

        val title = selectFirst(".title, h2, h3, .entry-title")?.text()?.trim()?.ifBlank { null }
            ?: a.attr("title").trim().ifBlank { null }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null

        val poster = selectFirst("img[data-src], img[src], img[data-lazy-src]")
            ?.let {
                it.attr("data-src").ifBlank {
                    it.attr("data-lazy-src").ifBlank { it.attr("src") }
                }
            }
            ?.takeIf { it.isNotBlank() }
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
        val poster = doc.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = doc.selectFirst("meta[itemprop=description]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("[class*=description]")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val tags = doc.select("a[rel=tag], a[href*=/tag/], [class*=categor] a").map { it.text().trim() }
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

        // The video url can be in three places, in order of reliability.
        val candidates = LinkedHashSet<String>()

        // 1. what the page declares about itself
        doc.selectFirst("meta[itemprop=contentUrl]")?.attr("content")
            ?.replace("&amp;", "&")?.trim()
            ?.takeIf { it.startsWith("http") }
            ?.let { candidates.add(it) }

        // 2. the player iframe's payload. Posts without a contentUrl still carry the same url
        //    inside it: q is base64url of "post_id=…&tag=<video…><source src='…m3u8'>…".
        doc.select("iframe[src]").forEach { frame ->
            val src = frame.attr("src").replace("&amp;", "&")
            Regex("[?&]q=([^&]+)").find(src)?.groupValues?.get(1)?.let { q ->
                decodePlayerTag(q)?.let { candidates.add(it) }
            }
        }

        // 3. anything else that is literally a playlist or file in the page
        Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""")
            .findAll(html).map { it.value.replace("&amp;", "&") }
            .filter { !isAdHost(it) }
            .forEach { candidates.add(it) }

        val links = candidates.filter { it.startsWith("http") && !isAdHost(it) }
        if (links.isEmpty()) return false

        links.forEachIndexed { index, url ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = if (index == 0) "$name (HLS)" else "$name (${index + 1})",
                    url = url,
                    type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    // The capture is specific about this: the browser sent video.twimg.com an
                    // Origin and no Referer at all, and the CDN echoed the Origin back. A foreign
                    // Referer is what gets a 403 here.
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to userAgent(),
                        "Origin" to mainUrl,
                        "Accept" to "*/*"
                    )
                }
            )
        }
        return true
    }

    /**
     * Pulls the media url out of the player iframe's base64 "q" payload.
     *
     * url-encoded base64url, so it has to be undone in that order: percent-decode, map -_ back
     * to +/, restore the padding the url may have eaten, base64-decode, then percent-decode the
     * html that comes out. Without the padding step the decoder throws on any value whose length
     * is not a multiple of four, and the link silently disappears.
     */
    private fun decodePlayerTag(qRaw: String): String? = runCatching {
        val q = java.net.URLDecoder.decode(qRaw, "UTF-8")
            .replace(' ', '+')
            .replace('-', '+')
            .replace('_', '/')
            .trimEnd('=')
        val padded = q + "=".repeat((4 - q.length % 4) % 4)
        val raw = android.util.Base64.decode(padded, android.util.Base64.NO_WRAP)
        val text = String(raw, Charsets.UTF_8)
        val tag = java.net.URLDecoder.decode(text, "UTF-8")
        Regex("""src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            .find(tag)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
    }.getOrNull()

    private fun isAdHost(url: String): Boolean {
        val u = url.lowercase()
        return listOf(
            "doubleclick", "googlesyndication", "google-analytics", "googletagmanager",
            "exoclick", "juicyads", "popads", "onclickads", "adsco.re", "magsrv",
            "gammacdn.com/500x281", "cloudflareinsights", "facebook.net", "twitter.com/i/ads"
        ).any { u.contains(it) }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
