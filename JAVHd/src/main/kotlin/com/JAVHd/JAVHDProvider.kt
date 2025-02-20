package com.JAVHd

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.FormBody

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
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..7) {
            val document = app.get("$mainUrl/search/video/?s=$query&page=$i").document
            //val document = app.get("${mainUrl}/page/$i/?s=$query").document

            val results = document.select("div.video").mapNotNull { it.toSearchResult() }

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
        val episodeList = doc.select(".button_style .button_choice_server")
        episodeList.forEach { item->
            var link = "atob\\('(.*)'\\)".toRegex().find(item.attr("onclick"))?.groups?.get(1)?.value.toString()
            loadExtractor(base64Decode(link),subtitleCallback,callback)
        }

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
                                if(text != null && text.size > 0 && text[0].text() == "Download")
                                {
                                    val url = "$subtitleCatUrl${text[0].attr("href")}"
                                    subtitleCallback.invoke(
                                        SubtitleFile(
                                            language.replace("\uD83D\uDC4D \uD83D\uDC4E",""),  // Use label for the name
                                            url     // Use extracted URL
                                        )
                                    )
                                }
                            } catch (e: Exception) { }
                        }

                    }
                }

            }
        } catch (e: Exception) { }



        return true
    }
}
