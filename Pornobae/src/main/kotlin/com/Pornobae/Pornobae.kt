package com.Pornobae

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.*

class Pornobae : MainAPI() {
    override var mainUrl              = "https://pornobae.com"
    override var name                 = "Pornobae"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
            "category/brazzers" to "Brazzers",
            "category/nubiles-porn/brattysis" to "Bratty Sis",
            "category/adult-time" to "Adult Time",
            "category/nubiles-porn/mom-lover" to "MILF",
            "category/nubiles-porn/brattysis" to "Bratty Sis",
            "category/bangbros" to "Bangbros",
            "category/brazzers" to "Brazzers",
            "category/babes-network" to "Babes Network",
            "category/teamskeet/family-strokes" to "Family Strokes",
            "category/adult-empire/my-pervy-family" to "My Pervy Family",
            "category/nubiles-porn/nfbusty" to "NF Busty",
            "category/adult-time/pure-taboo" to "Pure Taboo",

    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url:String
        if (page==1)
        {
            url = "$mainUrl/${request.data}"
        }
        else
        {
            url ="$mainUrl/${request.data}/page/$page"
        }
        val document = app.get(url).document
        val home     = document.select("div.videos-list article").mapNotNull {
             it.toSearchResult() }

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
        val title     = fixTitle(this.select("header span").text().substringAfter(" – ").trim())
        val href      = fixUrl(this.select("a").attr("href"))
        val posterUrl = fixUrlNull(this.select("img").attr("data-src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..5) {
            val document = app.get("${mainUrl}/search/$query/page/$i").document

            val results = document.select("#primary article").mapNotNull { it.toSearchResult() }

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

        val title       = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringAfter("–") ?:""
        val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val recommendations = document.select("article").map {
            val rectitle     = fixTitle(it.select("header span").text().substringAfter(" – ").trim())
            val rechref      = fixUrl(it.select("a").attr("href"))
            val recposterUrl = fixUrlNull(it.select("img").attr("data-src"))
            newTvSeriesSearchResponse(rectitle, rechref, TvType.TvSeries) {
                this.posterUrl = recposterUrl
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
        document.select("div.responsive-player").map { res ->
            val href=res.select("iframe").attr("src")
            loadExtractor(href,subtitleCallback, callback)
        }

        return true
    }
}


class PornobaeExtractor : StreamWishExtractor() {
    override var mainUrl = "https://tubexplayer.com"
 }