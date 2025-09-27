package com.javtiful

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink


class Javtiful : MainAPI() {
    override var mainUrl = "https://javtiful.com"
    override var name = "Javtiful"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "videos/sort=being_watched" to "Being Watched",
        "videos/sort=most_viewed" to "Most Viewed",
        "videos/sort=top_favorites" to "Top Favorites",
        "recommendation" to "Recommendation",
        "uncensored" to "Uncensored"
        )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/?page=$page").document
        val home =  document.select("div.card.border-0").drop(1).map { it.toSearchResult() }
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
        val title     = this.select("div.d-flex.align-items-center a").attr("title").trim()
        val href      = fixUrl(this.select("a").attr("href"))
        val posterUrl = fixUrlNull(this.select("a img").attr("data-src"))
        val quality= getQualityFromString(this.select("span.label-hd").text())
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality= quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document= app.get("$mainUrl/search/videos?search_query=$query").document
        val searchResponse=document.select("div.card.border-0").map { it.toSearchResult() }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title =  document.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim() ?: "Unknown"
        val tags = document.select("div:nth-child(2) > div.video-details__item_links a").map { it.text() }
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim() ?: "Unknown"
        val recommendations= document.select("div.card.border-0").map { it.toSearchResult() }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations=recommendations
            this.tags=tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document= app.get(data).document
        val token= document.selectFirst("#token_full")?.attr("data-csrf-token") ?:""
        val script = document.selectFirst("script:containsData(vcpov)")?.data()
        val postid = script?.let { Regex("vcpov\\s+=\\s+`(.*?)`").find(it)?.groupValues?.get(1) } ?: ""
        val form= mapOf("video_id" to postid,"pid_c" to "","token" to token)
        val m3u8= app.post("$mainUrl/ajax/get_cdn", data = form).parsedSafe<Response>()?.playlists
        if (m3u8!=null)
        {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8,
                    INFER_TYPE
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }

            )
        }
        return true
    }


    data class Response(
        @JsonProperty("playlists_active")
        val playlistsActive: Long,
        val playlists: String,
        @JsonProperty("playlist_source")
        val playlistSource: String,
    )

}