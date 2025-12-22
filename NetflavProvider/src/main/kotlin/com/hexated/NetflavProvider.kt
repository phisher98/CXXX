package com.anhdaden

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element

class NetflavProvider : MainAPI() {
    override var mainUrl = "https://netflav.com"
    override var name = "Netflav"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW,
    )
    val cookies = mapOf("i18next" to "en")

    override val mainPage = mainPageOf(
        "$mainUrl/censored?page=" to "Censored",
        "$mainUrl/uncensored?page=" to "Uncensored",
        "$mainUrl/chinese-sub?page=" to "Chinese sub",
        "$mainUrl/all?genre=Sexy&page=" to "Sexy",
        "$mainUrl/all?genre=School&page=" to "School",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page, referer = "$mainUrl/", cookies = cookies).document
        val script = document.select("script").findLast { it.data().contains("preview_hp") }?.data() ?: ""
        val home = document.select("div.grid_0_cell").mapNotNull {
            it.toSearchResult(script)
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(script: String): SearchResponse {
        val title = this.selectFirst("div.grid_0_title")?.text()?.trim() ?: ""
        var code = removeSquareBracketsContent(title.split(" ").get(0))
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: "")
        var posterUrl = if (script.contains("\"code\":\"${code}\",\"preview_hp\":\"")) {
            script.substringAfter("\"code\":\"${code}\",\"preview_hp\":\"").substringBefore("\"")
        } else if (script.contains("\"code\":\"${code.drop(1)}\",\"preview_hp\":\"")) {
            script.substringAfter("\"code\":\"${code.drop(1)}\",\"preview_hp\":\"").substringBefore("\"")
        } else {
            code = title.split(" ").get(0) + " " + title.split(" ").get(1)
            script.substringAfter("\"code\":\"${code}\",\"preview_hp\":\"").substringBefore("\"")
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?type=title&keyword=$query", referer = "$mainUrl/", cookies = cookies).document
        val script = document.select("script").findLast { it.data().contains("preview_hp") }?.data() ?: ""

        return document.select("div.grid_0_cell").map {
            it.toSearchResult2(script)
        }
    }

    private fun Element.toSearchResult2(script: String): SearchResponse {
        val title = this.selectFirst("div.grid_0_title")?.text()?.trim() ?: ""
        var code = removeSquareBracketsContent(title.split(" ").get(0))
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: "")
        var posterUrl = if (script.contains("\",\"code\":\"${code}\",")) {
            script.substringBefore("\",\"code\":\"${code}\",").substringAfterLast("\"preview\":\"")
        } else if (script.contains("\",\"code\":\"${code.drop(1)}\",")) {
            script.substringBefore("\",\"code\":\"${code.drop(1)}\",").substringAfterLast("\"preview\":\"")
        } else {
            code = title.split(" ").get(0) + " " + title.split(" ").get(1)
            script.substringBefore("\",\"code\":\"${code}\",").substringAfterLast("\"preview\":\"")
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url, referer = "$mainUrl/", cookies = cookies)
        val document = request.document
        
        val title = document.select("div.videodetail_2_title")?.text()?.trim().toString()
        val link = "https://" + document.select("script").findLast { it.data().contains(",\"src\":\"https://") }?.data()?.substringAfter(",\"src\":\"https://")?.substringBefore("\"") ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val year = document.selectFirst("#video-details > div:nth-child(4) > div:nth-child(2) > div.videodetail_2_field_values")?.text()?.split("-")?.get(0)?.toIntOrNull()
        val actors = document.select("a[href^='/all?actress=']").map { it.text() }
        val tags = document.select("a[href^='/all?genre=']").map { it.text() }
        val recommendations = app.get("https://netflav5.com/api98/video/getRelatedVideo?videoId=${url.substringAfter("?id=")}", referer = "$mainUrl/", cookies = cookies)
            .parsedSafe<Response>()?.result?.docs?.mapNotNull { it ->
                val posterUrl = it.preview_hp
                newMovieSearchResponse(it.title_en, "${mainUrl}/video?id=${it.videoId}", TvType.NSFW) {
                    this.posterUrl = posterUrl
                }
            }

        return newMovieLoadResponse(title, url, TvType.NSFW, link) {
            this.posterUrl = poster
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        val response = document.select("script").find { it.data().contains("eval(function(p,a,c,k,e,d)") }?.data()?.let { getAndUnpack(it) } ?: ""
        listOf("hls4", "hls2").forEach { key ->
            val link = response.substringAfter("\"$key\":\"").substringBefore("\"")
            if (link.isNotEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name [$key]",
                        fixUrl(link, getBaseUrl(data)),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$data/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }
}
