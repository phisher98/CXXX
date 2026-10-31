package com.watchuu.desihub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder
import java.net.URLEncoder
import org.jsoup.nodes.Element

/**
 * desihub.sh - Indian adult content site (Next.js, server-rendered).
 *
 * Built from a captured network log (CS3 Extension Builder):
 *  - listing cards : a.transform.overflow-hidden -> href /post/{slug}; title from img[alt]
 *                    ("Featured image for <title>"); poster is a /_next/image?url=<encoded> proxy
 *  - home (Latest) : / then /page/{n}
 *  - explore       : /explore/{n}
 *  - feed          : /feed
 *  - search        : /x/{query}  (and /x/{query}/{n})
 *  - detail        : /post/{slug} -> og:title / og:image, <source> mp4 in the page
 *  - streams       : direct mp4 from video.downloaddirect.xyz/{uuid}.mp4 (Referer downloaddirect.xyz)
 */
class DesiHubProvider : MainAPI() {
    override var mainUrl = "https://desihub.sh"
    override var name = "Desihub"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val streamReferer = "https://downloaddirect.xyz/"
    private val cf = CloudflareKiller()

    override val mainPage = mainPageOf(
        "" to "Latest",
        "explore" to "Explore",
        "feed" to "Feed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when (request.data) {
            "" -> if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page"
            "explore" -> "$mainUrl/explore/$page"
            "feed" -> "$mainUrl/feed"
            else -> "$mainUrl/${request.data.trim('/')}"
        }
        val items = app.get(url, referer = "$mainUrl/", interceptor = cf).document
            .select("a.transform.overflow-hidden").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        // /feed isn't paged; Latest and Explore keep loading the next page
        val hasNext = items.isNotEmpty() && request.data != "feed"
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val out = LinkedHashMap<String, SearchResponse>()
        for (p in 1..3) {
            val url = if (p == 1) "$mainUrl/x/$q" else "$mainUrl/x/$q/$p"
            val found = runCatching {
                app.get(url, referer = "$mainUrl/", interceptor = cf).document
                    .select("a.transform.overflow-hidden").mapNotNull { it.toSearchResult() }
            }.getOrNull().orEmpty()
            val fresh = found.filter { out[it.url] == null }
            if (fresh.isEmpty()) break
            fresh.forEach { out.putIfAbsent(it.url, it) }
        }
        return out.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/", interceptor = cf).document
        val title = (doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("h2")?.text()
            ?: doc.title().substringBefore(" - ")).trim().ifBlank { "Video" }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: realImage(doc.selectFirst("img[alt]")?.attr("src"))
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val tags = doc.select("a[href*=/category/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, referer = "$mainUrl/", interceptor = cf).text
        var found = false
        Regex("""https?://video\.downloaddirect\.xyz/[a-z0-9\-]+\.mp4""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.value }.distinct().forEach { link ->
                callback.invoke(
                    newExtractorLink(name, name, link, ExtractorLinkType.VIDEO) {
                        this.referer = streamReferer
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        return found
    }

    /** A card's image is a Next.js proxy: /_next/image?url=<encoded real url>&w=..&q=.. */
    private fun realImage(src: String?): String? {
        if (src.isNullOrBlank()) return null
        if (src.contains("/_next/image")) {
            Regex("""[?&]url=([^&]+)""").find(src)?.groupValues?.get(1)?.let { enc ->
                runCatching { URLDecoder.decode(enc, "UTF-8") }.getOrNull()?.takeIf { it.startsWith("http") }?.let { return it }
            }
        }
        return fixUrlNull(src)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").takeIf { it.contains("/post/") } ?: return null
        val url = fixUrl(href)
        // the featured <img> carries alt="Featured image for <title>" and the proxied poster src
        val featured = select("img").firstOrNull { it.attr("alt").isNotBlank() } ?: selectFirst("img")
        val title = featured?.attr("alt")?.trim().orEmpty()
            .removePrefix("Featured image for").trim()
            .ifBlank { href.substringAfterLast("/").substringBeforeLast("-").replace("-", " ").trim() }
            .ifBlank { href.substringAfterLast("/").replace("-", " ") }
        val poster = realImage(featured?.attr("src"))
        return newMovieSearchResponse(title, url, TvType.NSFW) { this.posterUrl = poster }
    }
}
