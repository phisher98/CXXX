package com.Mangoporn

//import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Mangoporn : MainAPI() {
    override var mainUrl              = "http://mangoporn.net"
    override var name                 = "Mangoporn"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "genres/porn-movies" to "Latest Release",
        "genre/russian" to "Russian",
        "studios/brazzers" to "Brazzers",
        "studios/bang-bros-productions" to "Bang Bros",
        "studios/evil-angel" to "Evil Angle",
        "studios/hustler" to "Hustler",
        "studios/devils-film" to "Devil Film",
        "studios/reality-kings" to "Reality Kings",
        "genre/family-roleplay" to "Family Roleplay",
        "genre/parody" to "Parody",
        "genre/18-teens" to "18+ Teens",
        "genre/anal" to "Anal",
        "genre/big-boobs" to "Big Boobs",
        "genre/blondes" to "Blondes",
        "genre/blowjobs" to "Blowjobs",
        "genre/lesbian" to "Lesbian",
        "genre/deep-throat" to "Deep Throat",
        "genre/cumshots" to "Cumshots",
        "genre/facials" to "Facials",
        "genre/bdsm" to "BDSM",
        "genre/threesomes" to "Threesomes",
        "genre/gangbang" to "Gangbang",
        "genre/redheads" to "Red Heads",
        "genre/squirting" to "Squirting",
        "genre/milf" to "MILF",
        "genre/asian" to "Asian",
        "genre/big-butt" to "Big Butt",
        "genre/big-cock" to "Big Cock"

    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            val document = app.get("$mainUrl/${request.data}/page/$page").document
            val home = document.select("div.items > article")
                .mapNotNull { it.toSearchResult() }
            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = false
                ),
                hasNext = true
            )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title     = this.select("div h3").text()
        val href      = fixUrl(this.select("div h3 a").attr("href"))
        val posterUrl = this.select("div.poster > img").attr("data-wpfc-original-src")
        return if (!posterUrl.contains(".jpg")) {
            val poster=this.select("div.poster > img").attr("src")
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        } else {
            val poster=posterUrl
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }

    private fun Element.toSearchingResult(): SearchResponse {
        val title = this.select("div.details a").text()
        val href = fixUrl(this.select("div.image a").attr("href"))
        val posterUrl = this.select("div.image img").attr("data-wpfc-original-src")
        return if (!posterUrl.contains(".jpg")) {
            val poster=this.select("div.poster > img").attr("src")
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        } else {
            val poster=posterUrl
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..2) {
            val document = app.get("${mainUrl}/page/$i/?s=$query").document

            val results = document.select("article")
                .mapNotNull { it.toSearchingResult() }

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

        val title = document.selectFirst("div.data > h1")?.text().toString()
        val poster = document.selectFirst("div.poster > img")?.attr("data-wpfc-original-src")?.trim().toString()
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
        document.select("div#pettabs > ul a").map {
            val link=it.attr("href")
            loadExtractor(link,subtitleCallback, callback)
        }
        return true
    }
}
