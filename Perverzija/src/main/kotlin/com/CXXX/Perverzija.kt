package com.CXXX

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element

class Perverzija : MainAPI() {
    override var name = "Perverzija"
    override var mainUrl = "https://tube.perverzija.com"
    override val supportedTypes = setOf(TvType.NSFW)

    override val hasDownloadSupport = true
    override val hasMainPage = true

    private val cfInterceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
        "$mainUrl/featured-scenes/page/%d/?orderby=date" to "Featured",
        "$mainUrl/studio/onlyfans/page/%d/" to "Onlyfans",
        "$mainUrl/studio/vxn/page/%d/" to "Vxn",
        "$mainUrl/studio/brazzers/page/%d/" to "Brazzers",
        "$mainUrl/studio/private/page/%d/" to "Private",
        "$mainUrl/studio/nubiles/page/%d/" to "Nubiles",
        "$mainUrl/studio/realitykings/page/%d/" to "Reality Kings",
        "$mainUrl/studio/bangbros/page/%d/" to "Bangbros",
        "$mainUrl/studio/naughtyamerica/page/%d/" to "Naughty America",
        "$mainUrl/studio/vxn/blacked/page/%d/" to "Blacked",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data.format(page), interceptor = cfInterceptor, timeout = 100L).document
        val home = document.select("div.row div div.post").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name, list = home, isHorizontalImages = true
            ), hasNext = true
        )
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val posterUrl = fixUrlNull(this.select("dt a img").attr("src"))
        val title = this.select("dd a").text() ?: return null
        val href = fixUrlNull(this.select("dt a").attr("href")) ?: return null

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }

    }

    private fun Element.toSearchResult(): SearchResponse? {
        val posterUrl = fixUrlNull(this.select("div.item-thumbnail img").attr("src"))
        val title = this.select("div.item-head a").text() ?: return null
        val href = fixUrlNull(this.select("div.item-head a").attr("href")) ?: return null

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = if (query.contains(" ")) {
        "$mainUrl/page/$page/?s=${query.replace(" ", "+")}&orderby=date"
        } else {
            "$mainUrl/tag/$query/page/$page/"
        }

        val results = app.get(url, interceptor = cfInterceptor).document
            .select("div.row div div.post").mapNotNull {
                it.toSearchResult()
        }.distinctBy { it.url }

        val hasNext = if (results.isEmpty()) false else true
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cfInterceptor).document

        val poster = document.select("div#featured-img-id img").attr("src")
        val title = document.select("div.title-info h1.light-title.entry-title").text()
        val pTags = document.select("div.item-content p")
        val description = StringBuilder().apply {
            pTags.forEach {
                append(it.text())
            }
        }.toString()

        val tags = document.select("div.item-tax-list div a").map { it.text() }

        val recommendations =
            document.select("div.related-gallery dl.gallery-item").mapNotNull {
                it.toRecommendationResult()
            }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, interceptor = cfInterceptor)
        val document = response.document

        val iframeUrl = document.select("div#player-embed iframe").attr("src")

        Xtremestream().getUrl(iframeUrl, data, subtitleCallback, callback)

        return true
    }
}
