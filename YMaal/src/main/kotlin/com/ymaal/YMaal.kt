package com.ymaal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId

class YMaal : MainAPI() {
    override var mainUrl = "https://ymaal.co"
    override var name = "YMaal"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.NSFW)

    private fun toResult(post: Element): SearchResponse? {
        val url = post?.attr("href") ?: return null
        val title = post.selectFirst("h2.title")?.text() ?: ""
        val imageUrl = post.selectFirst("div.thumbnail-container img")?.attr("src") ?: null
    
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = imageUrl
        }
    }
    
    override val mainPage = mainPageOf(
        "" to "Latest",
        "channel/ullu" to "Ullu",
        "channel/altt" to "Altt",
        "channel/feel" to "Feel",
        "channel/kooku" to "Kooku",
        "channel/primeplay" to "PrimePlay",
        "channel/hitprime" to "HitPrime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        val document = app.get(url).document
        val home = document.select("a.video-card").mapNotNull { toResult(it) }
        return newHomePageResponse(HomePageList(request.name,home,true))
    }

    override suspend fun search(query: String,page:Int): SearchResponseList? {
        val document = app.get("$mainUrl/page/$page/?s=$query").document
        val searchResult:List<SearchResponse> = document.select("a.video-card").mapNotNull { toResult(it) }
        return searchResult.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        var title = doc.selectFirst("h1.video-title")?.text() ?: "$name"
        var description = doc.select("div.description").text().removePrefix("Description")
        var posterUrl = doc.select("meta[property^=og:image]").attr("content")
        val streamLink = doc.selectFirst("video source")?.attr("src") ?: ""
        
        return newMovieLoadResponse(title, url,TvType.Movie, streamLink) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isEmpty()) return false
        callback.invoke(
            newExtractorLink("$name","$name",data)
        )
        return true
    }
}