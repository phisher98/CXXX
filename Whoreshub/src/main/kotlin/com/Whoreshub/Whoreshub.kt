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
        val href = fixUrlNull(this.attr("href"))?.takeIf { it.contains("/videos/") } ?: return null
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.startsWith("http") || it.startsWith("//") })

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encoded = query.trim().replace(" ", "+")
        val document = app.get("${mainUrl}/search/${encoded.replace("+", "-")}/").document
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
        val document = app.get(data, referer = "$mainUrl/").document
        val docText = document.html()

        // KVS player flashvars: video_url / video_alt_url[N] with matching *_text quality labels,
        // e.g. video_url: 'https://www.whoreshub.com/get_file/8/<hash>/771000/771931/771931.mp4/?v-acctoken=...'
        val jsLinks = mutableListOf<String>()
        Regex("""(video_url|video_alt_url\d*)\s*:\s*'([^']+)'""").findAll(docText).forEach { m ->
            val key = m.groupValues[1]
            var link = m.groupValues[2]
            if (link.startsWith("function/")) return@forEach // obfuscated variant (license_code) not supported
            if (!link.startsWith("http")) link = fixUrl(link)
            if (link in jsLinks) return@forEach
            jsLinks.add(link)
            val label = Regex("""${key}_text\s*:\s*'([^']*)'""").find(docText)?.groupValues?.get(1).orEmpty()
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = if (label.isNotBlank()) "${this.name} $label" else this.name,
                    url = link,
                    type = ExtractorLinkType.VIDEO
                ) {
                    // get_file links are tied to the watch page; send it as referer
                    this.referer = data
                    this.headers = mapOf("Origin" to mainUrl)
                    this.quality = Regex("""(\d{3,4})p""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""_(\d{3,4})p""").find(link)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Qualities.Unknown.value
                }
            )
        }

        // Fallback: embedded hosts (skip ad iframes on the site itself)
        var iframeFound = false
        if (jsLinks.isEmpty()) {
            document.select("div.player iframe[src], div.video-holder iframe[src], .embed-wrap iframe[src]").forEach { iframe ->
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.isNotBlank()) {
                    runCatching { if (loadExtractor(iframeSrc, data, subtitleCallback, callback)) iframeFound = true }
                }
            }
        }

        return jsLinks.isNotEmpty() || iframeFound
    }
}
