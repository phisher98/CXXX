package com.Xmaza

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Xmaza : MainAPI() {
    override var mainUrl              = "https://xmaza.net"
    override var name                 = "Xmaza"
    override val hasMainPage          = true
    override var lang                 = "hi"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "Home",
        "ullu-c14" to "Ullu",
        "triflicks" to "Triflicks",
        "primeplay-c1" to "PrimePlay",
        "kooku" to "Kooku",
        "atragii-c8" to "Atragii",
        "rabbit" to "Rabbit",
        "hunters" to "Hunters"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/page/$page").document
        val home     = document.select("div.videos a").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title     = fixTitle(this.attr("title")).trim()
        val href      = fixUrl(this.attr("href"))
        val posterUrl = fixUrlNull(this.attr("style").substringAfter("background-image: url('").substringBefore("'"))

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}?s=$query").document
        val results = document.select("div.videos a").mapNotNull { it.toSearchResult() }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title       = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
        val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
    

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val source=document.selectFirst("#my-video source")?.attr("src") ?:""
        callback.invoke(
            ExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = source,
                referer = data,
                quality = Qualities.Unknown.value
            )
        )
        return true
    }
}