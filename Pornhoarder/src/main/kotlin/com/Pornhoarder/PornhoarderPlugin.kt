package com.PornhoarderPlugin

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.FormBody

class PornhoarderPlugin : MainAPI() {
    override var mainUrl              = "https://pornhoarder.org"
    override var name                 = "Pornhoarder"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    private val ajaxUrl = "$mainUrl/ajax_search.php"

    override val mainPage = mainPageOf(
            "Latest" to "Latest Videos",
            "Popular" to "Popular Videos",
            "/trending-videos/" to "Trending Videos",
            "/random-videos/" to "Random Videos"
        )

    private fun getRequestBody (query: String, isLatest : Boolean, page:Int) : FormBody
    {
        return FormBody.Builder()
            .addEncoded("search", query)
            .addEncoded("sort", if (isLatest) {"0"} else {"2"})
            .addEncoded("date", "0")
            .addEncoded("servers[]", "40")
            .addEncoded("servers[]", "45")
            .addEncoded("servers[]", "12")
            .addEncoded("servers[]", "29")
            .addEncoded("servers[]", "25")
            .addEncoded("servers[]", "41")
            .addEncoded("servers[]", "46")
            .addEncoded("servers[]", "17")
            .addEncoded("servers[]", "44")
            .addEncoded("servers[]", "42")
            .addEncoded("servers[]", "43")
            .addEncoded("author", "0")
            .addEncoded("page", page.toString())
            .build()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if(request.data == "Latest" || request.data == "Popular")
        {
            val body = getRequestBody("",request.data == "Latest",page)
            val document = app.post(ajaxUrl, requestBody = body).document
            val responseList  = document.select(".video article").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = true),hasNext = true)

        }
        else
        {
            val document = app.get("$mainUrl${request.data}?page=$page").document
            val responseList  = document.select(".video article").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = true),hasNext = true)
        }
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select(".video-content h1").text().replace("| PornHoarder.tv","")
        val href = mainUrl + this.select(".video-link").attr("href")
        val posterUrl = this.selectFirst(".video-image.primary.b-lazy")?.attr("data-src")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..5) {
            val requestBody = getRequestBody(query,true,i)
            val document = app.post(ajaxUrl, requestBody = requestBody).document
            //val document = app.get("${mainUrl}/page/$i/?s=$query").document

            val results = document.select(".video article").mapNotNull { it.toSearchResult() }

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
        val serversList = mutableListOf<String>()
        val currentSrc = doc.select(".video-player iframe").attr("src")
        serversList.add(currentSrc)
        val servers = doc.select(".video-detail-servers")
        if(servers.isNotEmpty())
        {
            val urls = servers.select("li a")
            urls.forEach { item->
                val hostUrl = "$mainUrl${item.attr("href")}"
                val docurl = app.get(hostUrl).document
                val srcUrl = docurl.select(".video-player iframe").attr("src")
                serversList.add(srcUrl)
            }
        }
        serversList.forEach {item->
            val requestBody =FormBody.Builder()
                .addEncoded("play", "")
                .build()
            val doc1 = app.post(item,requestBody = requestBody).document
            val videoHosterUrl = doc1.select("iframe").attr("src")
            loadExtractor(videoHosterUrl,subtitleCallback,callback)
        }
        return true
    }
}
