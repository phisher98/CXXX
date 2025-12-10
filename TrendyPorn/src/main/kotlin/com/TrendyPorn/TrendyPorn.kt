package com.megix

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TrendyPorn : MainAPI() {
    override var mainUrl              = "https://www.trendyporn.com"
    override var name                 = "TrendyPorn"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/most-recent/" to "Most Recent",
        "$mainUrl/most-viewed/day/" to "Most Viewed(Day)",
        "$mainUrl/tag/onlyfans/" to "OnlyFans",
        "$mainUrl/most-viewed/week/" to "Most Viewed(Week)",
        "$mainUrl/most-viewed/month/" to "Most Viewed(Month)",
        "$mainUrl/most-viewed/" to "Most Viewed(All Time)",
        "$mainUrl/random/" to "Random",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + "page" + page + ".html").document
        val home = document.select("#wrapper > div.container > div:nth-child(4) > div div.well-sm").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("a").attr("title")
        val href = this.select("a").attr("href")
        val posterUrl = this.select("img").attr("data-original")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.get("${mainUrl}/search/${query}/page${page}.html").document
        val results = document.select("#wrapper > div.container > div:nth-child(4) > div div.well-sm").mapNotNull { it.toSearchResult() }
        val hasNext = if(results.isEmpty()) false else true
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.select("meta[property=og:title]").attr("content")
        val posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content")) ?:""

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
        ): Boolean {

        val document = app.get(data).document
        val link = document.select("source").attr("src")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = link
            )
        )
        return true
    }
}
