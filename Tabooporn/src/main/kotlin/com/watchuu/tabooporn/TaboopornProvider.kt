package com.watchuu.tabooporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * tabooporn.to — built from the capture of 2026-11-01 05:38.
 *
 * A KVS site (its player cookies are kt_*), which shapes everything here:
 *
 *   listing   div.item > a[href=/video/…], .title, img[data-original]   (36 per page)
 *   detail    /video/{id}/{slug}/
 *   video     the page carries a schema.org VideoObject:
 *               "contentUrl": "https://tabooporn.to/get_file/0/<token>.mp4/"
 *             which is the file the player streams. /get_file/ urls are also scattered through
 *             the page for the quality options, so those are collected as extra links.
 *
 * Pagination is KVS block loading rather than a page path, and the capture shows it exactly:
 *   /?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=post_date&from=2
 * Page 1 is the ordinary listing page, page N is that call with from=N, and the response is an
 * HTML fragment with the same div.item cards.
 */
class TaboopornProvider : MainAPI() {
    override var mainUrl = "https://tabooporn.to"
    override var name = "Tabooporn"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "most-popular" to "Most Popular",
        "top-rated" to "Top Rated",
        "latest-updates" to "Latest Updates"
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
        }.getOrNull()

    /** The KVS block a section paginates through. */
    private fun blockId(path: String): String = when {
        path.startsWith("most-popular") -> "list_videos_most_popular_videos"
        path.startsWith("top-rated") -> "list_videos_top_rated_videos"
        else -> "list_videos_most_recent_videos"
    }

    private fun pageUrl(path: String, page: Int): String {
        if (page > 1) {
            return "$mainUrl/?mode=async&function=get_block&block_id=${blockId(path)}" +
                "&sort_by=post_date&from=$page"
        }
        val base = "$mainUrl/" + path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        return base
    }

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val body = fetch(pageUrl(request.data, page))
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = cards(Jsoup.parse(body))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isBlank()) return emptyList()
        val body = fetch("$mainUrl/search/$q/") ?: return emptyList()
        return cards(Jsoup.parse(body))
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("div.item, div.video-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href*=/video/]") ?: return null
        val href = a.attr("href").trim()
        if (!href.contains("/video/")) return null

        val title = selectFirst(".title, .video-title")?.text()?.trim()?.ifBlank { null }
            ?: a.attr("title").trim().ifBlank { null }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null

        val poster = selectFirst("img[data-original], img[data-src], img[src]")
            ?.let {
                it.attr("data-original").ifBlank {
                    it.attr("data-src").ifBlank { it.attr("src") }
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

        val ld = doc.selectFirst("script[type=application/ld+json]")?.data().orEmpty()
        val title = jsonValue(ld, "name")
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Video"
        val poster = jsonValue(ld, "thumbnailUrl")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = jsonValue(ld, "description")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val tags = doc.select("[class*=categor] a, a[href*=/categories/]").map { it.text().trim() }
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

        // The player is configured by a flashvars object in the page:
        //   video_url:      '…/get_file/0/<token>.mp4/?v-acctoken=<token>'   (480p)
        //   video_alt_url:  '…'                                              (720p)
        //   video_alt_url2: '…'                                              (1080p)
        // The token is the whole point. The same file without ?v-acctoken= is what the
        // VideoObject's contentUrl points at, and it does not play - the site refuses it. So the
        // flashvars urls are the only genuine links, and they are per-post, which also means the
        // hover previews of the related videos further down never get in.
        val qualities = listOf(
            Triple("video_alt_url2", "video_alt_url2_text", "1080p"),
            Triple("video_alt_url", "video_alt_url_text", "720p"),
            Triple("video_url", "video_url_text", "480p")
        )
        val links = qualities.mapNotNull { (urlKey, textKey, fallback) ->
            val url = flashValue(html, urlKey) ?: return@mapNotNull null
            if (!url.startsWith("http")) return@mapNotNull null
            val label = flashValue(html, textKey) ?: fallback
            url to label
        }.distinctBy { it.first }

        if (links.isEmpty()) return false

        links.forEach { (url, label) ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name ($label)",
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

    /** Reads a single-quoted flashvars value, e.g. video_url: 'https://…'. */
    private fun flashValue(html: String, key: String): String? =
        Regex("""\b$key\s*:\s*'([^']+)'""")
            .find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.trim()
            ?.ifBlank { null }

    /** Pull a string field out of the page's JSON-LD without a JSON parser. */
    private fun jsonValue(html: String, field: String): String? =
        Regex("\"$field\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.replace("\\\"", "\"")
            ?.trim()
            ?.ifBlank { null }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
