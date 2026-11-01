package com.watchuu.porneec

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * porneec.com — built from the capture of 2026-11-01 05:40.
 *
 * WordPress, and it plays through the same clean-tube-player wrapper as taboodaddy:
 *
 *   listing   article.thumb-block.video-preview-item > a[href]   (30 per page)
 *             poster is the article's data-main-thumb, title is the anchor's title
 *   paging    path: /page/N/   - and /page/N/?s=… for a search
 *   search    /?s=<query>
 *   detail    /{slug}/
 *   video     an iframe to /wp-content/plugins/clean-tube-player/public/player-x.php?q=…
 *             whose q is base64url of "post_id=…&tag=<video…><source src='https://…mp4'>…".
 *             Decoding that is the only reliable way to the file: the page also carries
 *             unrelated b-cdn.net mp4s that are not this post's video.
 *
 * The capture shows a cf_clearance cookie, so the fetch falls back to CloudflareKiller if the
 * plain request is refused.
 */
class PorneecProvider : MainAPI() {
    override var mainUrl = "https://porneec.com"
    override var name = "Porneec"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Latest"
    )

    private fun userAgent(): String = UA

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): String? {
        val headers = mapOf(
            "User-Agent" to userAgent(),
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to referer
        )
        val plain = runCatching { app.get(url, headers = headers, timeout = 25L).text }.getOrNull()
        if (plain != null && looksLikePage(plain)) return plain

        return runCatching {
            app.get(url, headers = headers, interceptor = CloudflareKiller(), timeout = 40L).text
        }.getOrNull()?.takeIf { looksLikePage(it) }
    }

    /**
     * A page is good when it actually contains the site's own markup. Deciding by "does it look
     * like a challenge" does not work: Cloudflare puts challenge-platform and cf_chl in the html
     * of perfectly normal pages as part of its bot detection, so a blocklist of those markers
     * rejected the real home page and left the extension with nothing. Asking for the content we
     * are about to parse is the only test that holds in both directions.
     */
    private fun looksLikePage(html: String): Boolean =
        html.contains("<html", true) && (
            html.contains("thumb-block") || html.contains("player-x.php") ||
                html.contains("wp-content") || html.contains("<iframe")
            )

    private fun pageUrl(page: Int, query: String? = null): String {
        val base = if (page > 1) "$mainUrl/page/$page/" else "$mainUrl/"
        return if (query.isNullOrBlank()) base else "${base}?s=$query"
    }

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val body = fetch(pageUrl(page))
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = cards(Jsoup.parse(body))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isBlank()) return emptyList()

        val first = fetch(pageUrl(1, q))?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        if (first.isEmpty()) return first
        val second = fetch(pageUrl(2, q))?.let { Jsoup.parse(it) }?.let { cards(it) }.orEmpty()
        return (first + second).distinctBy { it.url }
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("article.video-preview-item, article.thumb-block")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank() || href.contains("?s=") || href.contains("/page/")) return null

        val title = selectFirst(".title, h2")?.text()?.trim()?.ifBlank { null }
            ?: a.attr("title").trim().ifBlank { null }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null

        val poster = attr("data-main-thumb").trim().ifBlank { null }
            ?: selectFirst("img[src], img[data-src]")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.trim()?.ifBlank { null }
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
        val plot = doc.selectFirst("[class*=description]")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val tags = doc.select("[class*=categor] a, a[href*=/tag/]").map { it.text().trim() }
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

        // the player wrapper's payload is where this post's own file lives
        doc.select("iframe[src]").forEach { frame ->
            val src = frame.attr("src").replace("&amp;", "&")
            Regex("[?&]q=([^&]+)").find(src)?.groupValues?.get(1)?.let { q ->
                decodePlayerTag(q)?.let { candidates.add(it) }
            }
        }

        // a page that serves the player inline
        doc.select("video source[src], video[src]").forEach { el ->
            val src = el.attr("src").ifBlank { el.attr("data-src") }.trim()
            if (src.startsWith("http")) candidates.add(src)
        }

        // last resort: any file url in the page
        if (candidates.isEmpty()) {
            Regex("""https?://[^\s"'<>\\]+\.(?:mp4|m3u8)[^\s"'<>\\]*""")
                .findAll(html).map { it.value.replace("&amp;", "&") }
                .filter { !isAdHost(it) }
                .forEach { candidates.add(it) }
        }

        var links = candidates.filter { it.startsWith("http") && !isAdHost(it) }.distinct()

        // Nothing decoded: the wrapper may be resolvable by Cloudstream itself.
        if (links.isEmpty()) {
            doc.select("iframe[src]").forEach { frame ->
                val embed = frame.attr("src").replace("&amp;", "&").trim()
                if (embed.startsWith("http")) {
                    val got = mutableListOf<ExtractorLink>()
                    runCatching {
                        loadExtractor(embed, "$mainUrl/", subtitleCallback) { got.add(it) }
                    }
                    if (got.isNotEmpty()) {
                        got.forEach { callback.invoke(it) }
                        links = got.map { it.url }
                    }
                }
            }
            if (links.isEmpty()) return false
        }

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

    /**
     * Pulls the media url out of the clean-tube-player base64 "q" payload.
     *
     * The value is url-encoded base64url, so it has to be decoded in that order: percent-decode,
     * map -_ back to +/, restore the padding the url may have eaten, base64-decode, then
     * percent-decode the html that comes out. Skipping the first step is what made this return
     * nothing - %3D padding left the decoder with a length it refused.
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
            "doubleclick", "googlesyndication", "exoclick", "juicyads",
            "popads", "onclickads", "cloudflareinsights", "sagaplayer.com/ads"
        ).any { u.contains(it) }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
