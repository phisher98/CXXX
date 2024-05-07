package com.Javpoint

//import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Javangel : MainAPI() {
    override var mainUrl              = "https://jav-angel.net"
    override var name                 = "Javangel"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "tag/uncen-leaked" to "Uncen Leaked",
        "tag/english-sub" to "English Sub",
        "tag/vr" to "VR",
        "category/uncensored" to "Uncensored",
        "tag/re-upload" to "Old Jav",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page == 1) {
            val document = app.get("$mainUrl/${request.data}/").document
            val home = document.select("#tdi_56 > div.tdb_module_loop > div")
                .mapNotNull { it.toSearchResult() }
            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = true
                ),
                hasNext = true
            )
        }
        else {
            val document = app.get("$mainUrl/${request.data}/$page/").document
            val home = document.select("#tdi_56 > div.tdb_module_loop > div")
                .mapNotNull { it.toSearchResult() }
            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = true
                ),
                hasNext = true
            )
        }
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title     = this.select("h3 a").attr("title").trim()
        val href      = fixUrl(this.select("h3 a").attr("href"))
        val posterUrl = fixUrlNull(this.select("span").attr("data-img-url"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..2) {
            val document = app.get("${mainUrl}/search/video/?s=$query&page=$i").document

            val results = document.select("ul.videos > li").mapNotNull { it.toSearchResult() }

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
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().toString()
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val recommendations =
            document.select("ul.videos.related >  li").map {
                val recomtitle = it.selectFirst("div.video > a")?.attr("title")?.trim().toString()
                val recomhref = it.selectFirst("div.video > a")?.attr("href").toString()
                val recomposterUrl = it.select("div.video > a > div > img").attr("src")
                val recomposter="https://javdoe.sh$recomposterUrl"
                newAnimeSearchResponse(recomtitle, recomhref, TvType.NSFW) {
                    this.posterUrl = recomposter
                }
            }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
            this.recommendations=recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        document.select("div.jav_streaming > a").forEach {
            val link=it.attr("href").substringAfter("','").substringBefore("'")
            loadExtractor(link,subtitleCallback, callback)
        }
        return true
    }
}
