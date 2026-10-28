package com.freeuseporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.nodes.Element

/**
 * freeuseporn.com - custom "theme2026" tube site (no public JSON API).
 *
 * Built from a captured network log:
 *  - listing cards : a.thumb-wrap-link[href^=/video/] -> title h3 (fallback img alt), poster inner img[src]
 *  - home rows     : / (latest) and /videos/<category> (mind-control, forced, ...); pagination ?page=N
 *  - search        : GET /search/videos/<query>
 *  - detail        : /video/<id>/<slug> -> title og:title, poster og:image
 *  - streams       : direct progressive MP4 at /media/videos/h264/<id>_<height>p.mp4 (built client-side by
 *                    the player). loadLinks reads whatever source URLs the page carries and, failing that,
 *                    probes the standard renditions for the id. Each link is marked with the <height>p the
 *                    file name declares - never a guess.
 */
class FreeusepornProvider : MainAPI() {
    override var mainUrl = "https://www.freeuseporn.com"
    override var name = "Freeuseporn"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val mediaHosts = listOf("https://www.freeuseporn.com", "https://www2.freeuseporn.com")
    // standard renditions to probe when the page markup doesn't spell the sources out
    private val probeHeights = listOf(2160, 1080, 720, 480, 360, 240)

    // category paths confirmed from the site's /videos/<slug> links
    override val mainPage = mainPageOf(
        "" to "Latest",
        "videos/mind-control" to "Mind Control",
        "videos/general-freeuse" to "General Freeuse",
        "videos/free-service" to "Free Service",
        "videos/forced" to "Forced",
        "videos/ignored-sex" to "Ignored Sex",
        "videos/glory-hole" to "Glory Hole",
        "videos/time-stop" to "Time Stop",
        "videos/japanese" to "Japanese",
        "videos" to "All Videos",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trim('/')
        val base = if (path.isEmpty()) "$mainUrl/" else "$mainUrl/$path"
        val url = if (page > 1) "$base?page=$page" else base
        val doc = app.get(url).document
        val items = doc.select("a.thumb-wrap-link").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        val hasNext = items.isNotEmpty() && doc.selectFirst("a[href*=\"page=${page + 1}\"]") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val doc = app.get("$mainUrl/search/videos/$q").document
        return doc.select("a.thumb-wrap-link").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = (doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" - ")
            ?: "Video").trim()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: videoId(url)?.let { "$mainUrl/media/videos/tmb/$it/default.jpg" }
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val tags = doc.select("a[href*=/videos/], a[rel=tag]").map { it.text().trim() }
            .filter { it.isNotBlank() && it.length <= 30 }.distinct().take(15)

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
        val id = videoId(data) ?: return false
        val html = app.get(data, referer = "$mainUrl/").text

        // 1) any source URLs the page/player already spells out, e.g. /media/videos/h264/<id>_1080p.mp4
        val found = LinkedHashMap<Int, String>() // height -> url (prefer www over www2 when both appear)
        Regex("""(?:https?:)?/{0,2}(?:[a-z0-9.\-]+)?/media/videos/h264/${id}_(\d{3,4})p\.mp4""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val h = m.groupValues[1].toIntOrNull() ?: return@forEach
                found.putIfAbsent(h, "$mainUrl/media/videos/h264/${id}_${h}p.mp4")
            }

        // 2) nothing in the markup (the player builds it in JS): probe the standard renditions
        if (found.isEmpty()) {
            probeHeights.forEach { h ->
                val candidate = "$mainUrl/media/videos/h264/${id}_${h}p.mp4"
                if (mediaExists(candidate)) found[h] = candidate
            }
        }

        if (found.isEmpty()) return false
        found.entries.sortedByDescending { it.key }.forEach { (h, url) ->
            callback.invoke(
                newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = h
                }
            )
        }
        return true
    }

    /** A cheap range request to confirm a rendition exists before offering it. */
    private suspend fun mediaExists(url: String): Boolean = try {
        val r = app.get(url, headers = mapOf("Range" to "bytes=0-1"), referer = "$mainUrl/")
        r.code in 200..299
    } catch (_: Exception) {
        false
    }

    private fun videoId(url: String): String? =
        Regex("""/video/(\d+)""").find(url)?.groupValues?.get(1)

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").takeIf { it.startsWith("/video/") || it.contains("/video/") } ?: return null
        val url = fixUrl(href)
        val img = selectFirst("img")
        val title = (selectFirst("h3")?.text()
            ?: img?.attr("title")?.takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: href.substringAfterLast("/").replace('-', ' '))
            .trim().ifBlank { return null }
        val poster = img?.let { it.attr("src").ifBlank { it.attr("data-src") } }?.takeIf { it.isNotBlank() }
        return newMovieSearchResponse(title, url, TvType.NSFW) { this.posterUrl = poster }
    }
}
