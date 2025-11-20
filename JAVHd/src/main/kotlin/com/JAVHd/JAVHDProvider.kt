package com.JAVHd

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class JAVHDProvider : MainAPI() {
    override var mainUrl              = "https://javhd.today"
    override var name                 = "JAV HD"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded
    val subtitleCatUrl = "https://www.subtitlecat.com"
    override val mainPage = mainPageOf(
            "/releaseday/" to "Release Day",
            "/recent/" to "Latest Upadates",
            "/popular/today/" to "Most View Today",
            "/popular/week/" to "Most View Week",
            "/jav-sub/" to "Jav Subbed",
            "/jav-sub/popular/year/" to "Most Viewed Jav Subbed",
            "/uncensored-jav/" to "Uncensored",
            "/reducing-mosaic/" to "Reduced Mosaic",
            "/amateur/" to "Amateur"
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            val document = if(page == 1)
            {
                app.get("$mainUrl${request.data}").document
            }
            else
            {
                if(request.name == "Jav Subbed" || request.name == "Uncensored" || request.name == "Reduced Mosaic" || request.name == "Amateur")
                {
                    app.get("$mainUrl${request.data}recent/$page").document
                }
                else
                {
                    app.get("$mainUrl${request.data}$page").document
                }
            }
            val responseList  = document.select("div.video").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = false),hasNext = true)

    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select(".video-title").text()
        val href = mainUrl + this.select(".thumbnail").attr("href")
        val posterUrl = this.selectFirst(".video-thumb img")?.attr("src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get("$mainUrl/search/video/?s=$query&page=$page").document
        val results = document.select("div.video").mapNotNull { it.toSearchResult() }
        val hasNext = if (results.isEmpty()) false else true
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
    

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).document
        runAllAsync(
            {
                val episodeList = doc.select(".button_style .button_choice_server")
                    episodeList.forEach { item ->
                    val link = item.attr("data-embed")
                    loadExtractor(base64Decode(link),subtitleCallback,callback)
                }
            },
            {
                getExternalSubtitile(doc, subtitleCallback)
            }
        )

        return true
    }

    suspend fun getExternalSubtitile(doc: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
            val javCode = "([a-zA-Z]+-\\d+)".toRegex().find(title)?.groups?.get(1)?.value
            if(!javCode.isNullOrEmpty())
            {
                val query = "$subtitleCatUrl/index.php?search=$javCode"
                val subDoc = app.get(query, timeout = 15).document
                val subList = subDoc.select("td a")
                for(item in subList)
                {
                    if(item.text().contains(javCode))
                    {
                        val fullUrl = "$subtitleCatUrl/${item.attr("href")}"
                        val pDoc = app.get(fullUrl, timeout = 10).document
                        val sList = pDoc.select(".col-md-6.col-lg-4")
                        for(item in sList)
                        {
                            try {
                                val language = item.select(".sub-single span:nth-child(2)").text()
                                val text = item.select(".sub-single span:nth-child(3) a")
                                if(text.isNotEmpty() && text[0].text() == "Download")
                                {
                                    val url = "$subtitleCatUrl${text[0].attr("href")}"
                                    subtitleCallback.invoke(
                                        newSubtitleFile(
                                            language.replace("\uD83D\uDC4D \uD83D\uDC4E",""),  // Use label for the name
                                            url     // Use extracted URL
                                        )
                                    )
                                }
                            } catch (_: Exception) { }
                        }

                    }
                }

            }
        } catch (_: Exception) { }
    }
}
