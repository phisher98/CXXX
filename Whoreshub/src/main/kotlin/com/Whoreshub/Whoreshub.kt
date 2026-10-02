package com.Whoreshub

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Whoreshub : MainAPI() {
    override var mainUrl              = "https://www.whoreshub.com"
    override var name                 = "Whoreshub"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/latest-updates" to "Latest",
        "${mainUrl}/most-popular" to "Most Popular",
        "${mainUrl}/top-rated" to "Top Rated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/${page}/").document
        val home = document.select("div.block-thumbs a.item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title").takeIf { it.isNotBlank() } ?: return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src")
            ?: this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encoded = query.trim().replace(" ", "+")
        val document = app.get("${mainUrl}/search/?q=$encoded").document
        val results = document.select("div.block-thumbs a.item").mapNotNull { it.toSearchResult() }
        searchResponse.addAll(results)
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        val posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val docText = document.toString()

        // Try JS variable extraction first (common tube format)
        val jsRegex = Regex("""(?:video_url|video_alt_url[23]?)\s*[:=]\s*['"]?(https?://[^'",\s]+)['"]?""")
        val jsLinks = jsRegex.findAll(docText).map { it.groupValues[1] }.distinct().toList()

        for (link in jsLinks) {
            if (link.isNotEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = link
                    ) {
                        this.referer = mainUrl
                        this.quality = Regex("""_(1080|720|480|360)p""")
                            .find(link)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: Qualities.Unknown.value
                    }
                )
            }
        }

        // Fallback: try iframes
        if (jsLinks.isEmpty()) {
            document.select("iframe[src]").forEach { iframe ->
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.isNotBlank()) {
                    runCatching { loadExtractor(iframeSrc, data, subtitleCallback, callback) }
                }
            }
        }

        return true
    }
}
