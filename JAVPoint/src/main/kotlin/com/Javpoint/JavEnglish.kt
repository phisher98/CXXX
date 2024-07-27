package com.Javpoint

//import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class JavEnglish : MainAPI() {
    override var mainUrl = "https://javenglish.cc"
    override var name = "Jav English"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "?filter=latest" to "Latest",
        "?filter=popular" to "Popular",
        "?filter=most-viewed" to "Most Viewed",
        "category/english-subbed-jav" to "English Subbed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.contains("category"))
        {
            val document = app.get("$mainUrl/${request.data}/page/$page").document
            val home = document.select("div.videos-list > article")
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
        val document = app.get("$mainUrl/page/$page/${request.data}").document
        val home = document.select("div.videos-list > article")
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

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("a > header > span").text()
        val href = fixUrl(this.select("a").attr("href"))
        val posterUrl = this.select("a > div.post-thumbnail img").attr("data-src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            posterHeaders = mapOf("Referer" to mainUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..2) {
            val document = app.get("${mainUrl}/page/$i/?s=$query").document

            val results = document.select("article")
                .mapNotNull { it.toSearchResult() }

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

        val title =
            document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
        val poster =
            document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().toString()
        val description =
            document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val recommendations =
            document.select("ul.videos.related >  li").map {
                val recomtitle = it.selectFirst("div.video > a")?.attr("title")?.trim().toString()
                val recomhref = it.selectFirst("div.video > a")?.attr("href").toString()
                val recomposterUrl = it.select("div.video > a > div > img").attr("src")
                val recomposter = "https://javdoe.sh$recomposterUrl"
                newAnimeSearchResponse(recomtitle, recomhref, TvType.NSFW) {
                    this.posterUrl = recomposter
                }
            }
        //println(poster)
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
            posterHeaders = mapOf("Referer" to mainUrl)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("div#sourcetabs > ul a").map {
                val link=it.attr("href")
                loadExtractor(link,subtitleCallback, callback)
        }
        return true
    }
}
