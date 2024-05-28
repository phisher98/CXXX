package com.Eporner

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*

class Eporner : MainAPI() {
    override var mainUrl              = "https://www.eporner.com"
    override var name                 = "Eporner"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
            "best-videos" to "Best Videos",
            "top-rated" to "Top Rated",
            "most-viewed" to "Most Viewed",
            "cat/milf" to "Milf",
            "cat/japanese" to "Japanese",
            "cat/hd-1080p" to "1080 Porn",
            "cat/4k-porn" to "4K Porn"

        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/$page/").document
        val home     = document.select("#vidresults div.mb").mapNotNull { it.toSearchResult() }

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
        val title     = fixTitle(this.select("div.mbunder p a").text()).trim()
        val href      = fixUrl(this.select("div.mbcontent a").attr("href"))
        val posterUrl = fixUrlNull(this.select("div.mbcontent a img").attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..10) {
            val document = app.get("${mainUrl}/search/$query/$i").document

            val results = document.select("#vidresults div.mb").mapNotNull { it.toSearchResult() }

            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }

            if (results.isEmpty()) break
        }

        return searchResponse
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
        val response = app.get(
            data, interceptor = WebViewResolver(Regex("""https://www\.eporner\.com/xhr/video"""))
        )
        val json=response.text
        val regex = Regex("labelShort\":\\s\"(.*?)\"|src\":\\s\"(.*)\"")
        val matches = regex.findAll(json)
        val srcList = mutableListOf<Pair<String, String>>()
        for (match in matches) {
            val labelShort = match.groupValues[1]
            val src = match.groupValues[2]
            srcList.add(labelShort to src)
        }
        srcList.forEach { (labelShort, src) ->
            if (!src.contains(".php")and(labelShort.isEmpty())) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = src,
                        referer = "",
                        quality = getQualityFromName(labelShort)
                    )
                )
            }
        }
        return true
    }
}