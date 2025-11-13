package com.megix

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.BigwarpIO
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.StreamTape

class Xmovies4u : MainAPI() {
    override var mainUrl              = "https://xmoviesforyou.com"
    override var name                 = "Xmovies4u"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "Latest",
        "/tag/onlyfans" to "Onlyfans",
        "/tag/vixen" to "Vixen",
        "/tag/tushy" to "Tushy",
        "/tag/dorcelclub" to "Dorcelclub",
        "/tag/deeper" to "Deeper",
        "/tag/blackedraw" to "Blackedraw",
        "/tag/blacked" to "Blacked",
        "/tag/puretaboo" to "Puretaboo",
        "/tag/sislovesme" to "Sislovesme",
        "/tag/oopsfamily" to "Oopsfamily",
        "/tag/familystrokes" to "Familystrokes",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl${request.data}/page/$page").document
        val home     = document.select("article.post").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
                list    = HomePageList(
                name    = request.name,
                list    = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title     = this.select("a").attr("title")
        val href      = this.select("a").attr("href")
        val posterUrl = this.select("a > img").attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.get("$mainUrl/page/$page/?s=$query").document
        val results = document.select("article.post").mapNotNull { it.toSearchResult() }
        val hasNext = if(results.isEmpty()) false else true
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title       = document.select("meta[property=og:title]").attr("content")
        val poster      = document.select("meta[property='og:image']").attr("content")
        val description = document.select("div.entry-content > p > span > span").text()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        document.select("span > span > a").amap {
            val text = it.text()
            val link = it.attr("href")

            if(text.contains("STREAMTAPE", true)) {
                StreamTape().getUrl(link, data, subtitleCallback, callback)
            } else if(text.contains("MIXDROP", true)) {
                MixDrop().getUrl(link, data, subtitleCallback, callback)
            } else if(text.contains("DOODSTREAM", true)) {
                DoodLaExtractor().getUrl(link, data, subtitleCallback, callback)
            } else if(text.contains("BIGWARP", true)) {
                BigwarpIO().getUrl(link, data, subtitleCallback, callback)
            } else {
                loadExtractor(
                    link,
                    "$mainUrl/",
                    subtitleCallback,
                    callback
                )
            }
        }
        return true
    }
}
