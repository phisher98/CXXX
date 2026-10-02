package com.CXXX

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class Porngrey : MainAPI() {
    override var mainUrl = "https://www.porngrey.net"
    override var name = "Porngrey"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "/videos_5/" to "Recent Videos",
        "/category/amateur_c6/" to "Amateur",
        "/category/blowjob_c10/" to "Blowjob",
        "/category/creampie_c5/" to "Creampie",
        "/category/teen_c11/" to "Teen",
        "/category/milf_c8/" to "MILF",
        "/category/anal_c7/" to "Anal",
        "/category/fetish_c18/" to "Fetish",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}$page/"
        }
        val document = app.get(url).document
        val home = document.select("a.cards__item, a[href*='/video/']").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home.distinctBy { it.url },
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href").takeIf { it.contains("/video/") } ?: return null
        val fullUrl = fixUrl(href)
        val title = this.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null

        val img = this.selectFirst("img")
        val poster = fixUrlNull(img?.attr("data-srcset") ?: img?.attr("src"))

        return newMovieSearchResponse(title, fullUrl, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (page in 1..3) {
            val url = "$mainUrl/search/?q=${query.encodeUri()}&sort=watch&page=$page"
            val document = app.get(url).document
            val results = document.select("a.cards__item, a[href*='/video/']").mapNotNull { it.toSearchResult() }
            val unique = results.filterNot { item -> searchResponse.any { it.url == item.url } }
            if (unique.isEmpty()) break
            searchResponse.addAll(unique)
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.title().substringBefore(" #").trim()

        val posterUrl = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = document.select("a[href*='/category/'], a[href*='/tag/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val actors = document.select("a[href*='/model/'], a[href*='/pornstar/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val recommendations = document.select("a.cards__item, a[href*='/video/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val html = document.html()

        // Extract direct video stream URLs from KT player
        val videoUrlRegex = Regex("""(?:video_url|video_alt_url\d*):\s*'([^']+)'""")
        val matches = videoUrlRegex.findAll(html).map { it.groupValues[1] }.distinct().toList()

        for (streamUrl in matches) {
            val cleanUrl = fixUrl(streamUrl)
            val quality = Regex("""-(\d+)p?\.mp4""").find(cleanUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Qualities.Unknown.value

            callback.invoke(
                newExtractorLink(
                    name = name,
                    source = name,
                    url = cleanUrl,
                ).apply {
                    this.quality = quality
                }
            )
        }

        // Also check for embedded iframes or external players if any
        val iframes = document.select("iframe[src]").map { it.attr("src") }
        for (iframe in iframes) {
            val fullIframe = fixUrl(iframe)
            if (!fullIframe.contains(mainUrl)) {
                loadExtractor(fullIframe, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }

    companion object {
        private fun String.encodeUri(): String =
            java.net.URLEncoder.encode(this, "UTF-8")
    }
}
