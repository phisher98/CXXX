package com.rowdyCSExtensions

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class DesiXFlix(val plugin: DesiXFlixPlugin) :
        MainAPI() { // all providers must be an intstance of MainAPI
    override var mainUrl = "https://desixflix.com"
    override var name = "DesiXFlix"
    override val supportedTypes = setOf(TvType.NSFW)

    override var lang = "en"

    // enable this when your provider has a main page
    override val hasMainPage = true

    // this function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val res = app.get(url)
        return searchResponseBuilder(res.document)
    }

    // this function gets called when you search on home page
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override val mainPage =
            mainPageOf(
                    "$mainUrl/page/" to "Latest videos",
                    "$mainUrl/hot-web-series/page/" to "Hot Web Series",
                    "$mainUrl/hot-short-film/page/" to "Hot Short Film",
                    "$mainUrl/alt-balaji/page/" to "ALTBalaji",
                    "$mainUrl/ullu/page/" to "Ullu",
                    "$mainUrl/hotslive/page/" to "Hots Live",
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val res = app.get(url)
        if (res.code != 200) throw ErrorLoadingException("Could not load data")
        val home = searchResponseBuilder(res.document)

        return newHomePageResponse(HomePageList(request.name, home, true), true)
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url)

        if (res.code != 200) throw ErrorLoadingException("Could not load data" + url)
        val poster =
                res.document
                        .selectFirst("div.video-player > meta[itemprop=thumbnailUrl]")
                        ?.attr("content")
        val embedUrl =
                res.document
                        .selectFirst(
                                "div.video-player > meta[itemprop=embedURL], meta[itemprop=contentURL]"
                        )
                        ?.attr("content")
        val details = res.document.select("div#video-about")
        val name = details.select("div.more > h2").text()

        return newMovieLoadResponse(name, url, TvType.NSFW, embedUrl) { this.posterUrl = poster }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Rushi", data)
        when {
            data.contains("d0000d") -> {
                D0000dExtractor().getUrl(data, data)?.forEach { link -> callback.invoke(link) }
            }
            data.contains("hotxseries") -> {
                var serverName = "HotxSeries"
                callback.invoke(ExtractorLink(serverName, serverName, data, "", 0))
            }
            else -> loadExtractor(data, subtitleCallback, callback)
        }
        return true
    }

    private fun searchResponseBuilder(webDocument: Document): List<SearchResponse> {
        val searchCollection =
                webDocument.select("article > a").mapNotNull { element ->
                    val title = element.attr("title")
                    val link = element.attr("href")
                    val poster = element.select("img").attr("data-src")

                    newMovieSearchResponse(title, link) { this.posterUrl = poster }
                }
        return searchCollection
    }
}
