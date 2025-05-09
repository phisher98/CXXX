package com.Javpoint

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Javgg : MainAPI() {
    override var mainUrl = "https://javgg.net"
    override var name = "Javgg"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "genre/stepmother" to "Stepmother",
        "genre/married-woman" to "Married Woman",
        "tag/english-subtitle" to "English Subtitle",
        "random" to "Random"
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
        val title = this.select("div.poster > a").attr("title")
        val href = fixUrl(this.select("div.poster > a").attr("href"))
        val posterUrl = this.select("div.poster > img").attr("src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toSearchingResult(): SearchResponse {
        val title = this.select("div.details a").text()
        val href = fixUrl(this.select("div.image a").attr("href"))
        val posterUrl = this.select("div.image img").attr("src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..2) {
            val document = app.get("${mainUrl}/jav/page/$i?s=$query").document

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
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("div.pframe iframe").forEachIndexed { index, iframe ->
            val src = iframe.attr("src")
            val link = if ("javggvideo.xyz" in src) {
                app.get(src).document.selectFirst("script:containsData(urlPlay)")?.data()
                    ?.let { Regex("urlPlay\\s*=\\s*'(.*?)'").find(it)?.groupValues?.getOrNull(1) }
            } else {
                app.get(src).document
                    .selectFirst("script:containsData(p,a,c,k,e,d)")?.data()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { JsUnpacker(it).unpack() }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Regex("file:\"(.*?)\"").find(it)?.groupValues?.getOrNull(1) }
            }
            link?.let {
                callback.invoke(
                    newExtractorLink(
                        source = "$name $index",
                        name = "$name $index",
                        url = it,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = ""
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }
}
