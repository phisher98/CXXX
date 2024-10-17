package com.Sextb

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.FormBody

class SextbProvider : MainAPI() {
    override var mainUrl              = "https://sextb.net"
    override var name                 = "Sextb"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    private val ajaxUrl = "$mainUrl/ajax/player"

    override val mainPage = mainPageOf(
            "/amateur" to "Amateur",
            "/censored" to "Censored",
            "/uncensored" to "Uncensord",
            "/subtitle" to "English Subtitled"
        )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            val document = app.get("$mainUrl${request.data}/pg-$page").document
            val responseList  = document.select(".tray-item").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = false),hasNext = true)

    }

    private fun getRequestBody (episode: String, filmId: String) : FormBody
    {
        return FormBody.Builder()
            .addEncoded("episode", episode)
            .addEncoded("filmId", filmId)
            .build()
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select(".tray-item-title").text()
        val href = mainUrl + this.select("a:nth-of-type(1)").attr("href")
        val posterUrl = this.selectFirst(".tray-item-thumbnail")?.attr("data-src")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..5) {
            val document = app.get("$mainUrl/search/$query/pg-$i").document
            //val document = app.get("${mainUrl}/page/$i/?s=$query").document

            val results = document.select(".tray-item").mapNotNull { it.toSearchResult() }

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

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString().replace("| PornHoarder.tv","")
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
    

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).document
        val episodeList = doc.select(".episode-list .btn-player")
        var sourceId = doc.selectFirst(".episode-list .btn-player").attr("data-source")
        episodeList.forEach { item->
            val requestBody = getRequestBody(item.attr("data-id"),sourceId)
            val doc =app.post(ajaxUrl,requestBody =requestBody).document
            val iframeSrc = doc.select("iframe").attr("src")
            val finalUrl = iframeSrc.replace("\\\"","").replace("\\/","\\").substringBefore("?")
            loadExtractor(finalUrl,subtitleCallback,callback)
        }


        return true
    }
}
