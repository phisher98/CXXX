package com.megix

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Onlyjerk : MainAPI() {
    override var mainUrl              = "https://onlyjerk.net"
    override var name                 = "Onlyjerk"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "/videos" to "Latest",
        "/featured" to "Featured",
        "/trending" to "Trending",
        "/onlyfans" to "Onlyfans",
        "/camwhores" to "Camwhores",
        "/fansly" to "Fansly",
        "/manyvids" to "Manyvids",
        "/porn" to "Porn",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl${request.data}/page/$page/").document
        val home     = document.select("div.tdb-block-inner > div.td-cpt-post").mapNotNull { it.toSearchResult() }

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
        val posterUrl = this.select("a > span").attr("style").substringAfter("url(").substringBefore(")")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.get("$mainUrl/page/$page/?s=$query").document
        val results = document.select("div.tdb-block-inner > div.td-cpt-post").mapNotNull { it.toSearchResult() }
        val hasNext = if(results.isEmpty()) false else true
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title       = document.select("meta[property=og:title]").attr("content")
        val poster      = document.select("meta[property='og:image']").attr("content")
        val description = document.select("meta[property=og:description]").attr("content")


        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        document.select(".player-wrap > iframe").amap {
            loadExtractor(
                it.attr("data-src"),
                referer = data,
                subtitleCallback,
                callback
            )
        }

        document.select(".button_choice_server").amap {
            val url = it.attr("onclick").substringAfter("'").substringBefore("'")
            loadExtractor(
                url,
                referer = data,
                subtitleCallback,
                callback
            )
        }

        document.select(".tabcontent > iframe").amap {
            loadExtractor(
                it.attr("data-litespeed-src"),
                referer = data,
                subtitleCallback,
                callback
            )
        }

        return true
    }
}
