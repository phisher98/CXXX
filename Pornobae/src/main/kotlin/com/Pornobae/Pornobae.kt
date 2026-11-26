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
            "category/bangbros" to "Bangbros",
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
        val home     = document.select("div.videos-list article, #primary article").mapNotNull {
             it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.posterOf(): String? {
        val img = selectFirst("img")
        return fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: attr("data-main-thumb").takeIf { it.isNotBlank() }
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href      = fixUrlNull(this.selectFirst("a[href]")?.attr("href")) ?: return null
        val rawTitle  = this.select("header span").text().ifBlank { this.selectFirst("a[title]")?.attr("title") ?: "" }
        val title     = fixTitle(rawTitle.substringAfter(" – ").trim()).ifBlank { return null }
        val posterUrl = posterOf()
        return newMovieSearchResponse(title, href, TvType.NSFW) {
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
            ?.substringAfter("–")?.trim() ?: ""
        val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val recommendations = document.select("article").mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
            this.recommendations=recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val iframes = document.select("div.responsive-player iframe[src]").map { fixUrl(it.attr("src")) }
        iframes.forEach { href ->
            loadExtractor(href, data, subtitleCallback, callback)
        }
        return iframes.isNotEmpty()
    }
}


class PornobaeExtractor : StreamWishExtractor() {
    override var mainUrl = "https://tubexplayer.com"
 }