package com.CXXX


import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder

class HStream : MainAPI() {
    override var mainUrl              = "https://hstream.moe"
    override var name                 = "HStream"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/search?order=recently-uploaded&page=" to "Latest",
        "${mainUrl}/search?order=view-count&page=" to "Popular",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("div.items-center div.w-full > a").mapNotNull {
            it.toSearchResult()
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

    private fun Element.toSearchResult(): SearchResponse {
        val title     = this.selectFirst("img")?.attr("alt") ?:"No Title"
        val href       = fixUrl(this.attr("href"))
        val posterUrl  = fixUrlNull(this.select("img").attr("src"))

        return newMovieSearchResponse(title, href,TvType.NSFW) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..2) {
            val document = app.get("${mainUrl}/search?search=$query&page=$i").document
            val results = document.select("div.items-center div.w-full > a").mapNotNull { it.toSearchResult() }
            searchResponse.addAll(results)
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title= document.selectFirst("div.relative h1")?.text()?.trim().toString()
        val poster= document.selectFirst("meta[property=og:image]")?.attr("content")
        val description=document.selectFirst("meta[property=og:description]")?.attr("content")
        val genres=document.select("ul.list-none.text-center li a").map { it.text() }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl= poster
            this.plot=description
            this.tags=genres
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val response = app.get(data)
        val cookies = response.headers.values("Set-Cookie")
        val cookieHeader = cookies.joinToString("; ") { it.substringBefore(";") }
        val token = cookies.flatMap { it.split(";") }
            .find { it.trim().startsWith("XSRF-TOKEN=") }
            ?.substringAfter("XSRF-TOKEN=")
            ?.let { URLDecoder.decode(it, "utf-8") }
            ?: ""
        val document = response.document
        val episodeId = document.selectFirst("input#e_id")!!.attr("value")
        val body = """{"episode_id": "$episodeId"}""".toRequestBody("application/json".toMediaType())

        val headers = mapOf(
            "Referer" to data,
            "Origin" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "X-XSRF-TOKEN" to token,
            "Cookie" to cookieHeader
        )

        val req = app.post("$mainUrl/player/api", headers = headers, requestBody = body).parsedSafe<PlayerApiResponse>()
        if (req != null) {
            val urlBase = (req.stream_domains.randomOrNull() ?: "") + "/" + req.stream_url
            val resolutions = listOfNotNull("720", "1080", if (req.resolution == "4k") "2160" else null)
            resolutions.forEach { resolution ->
                val url = urlBase + getVideoUrlPath(req.legacy != 0, resolution)
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = url,
                        INFER_TYPE
                    ) {
                        this.referer = ""
                        this.quality = getQualityFromName("${resolution}p")
                    }
                )
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = "English",
                        url = "$urlBase/eng.ass"
                    )
                )
            }
        } else {
            Log.e("Error", "Failed to fetch PlayerApiResponse!")
        }
        return true
    }

    private fun getVideoUrlPath(isLegacy: Boolean, resolution: String): String {
        return if (isLegacy) {
            if (resolution == "720") {
                "/x264.720p.mp4"
            } else {
                "/av1.$resolution.webm"
            }
        } else {
            "/$resolution/manifest.mpd"
        }
    }

    data class PlayerApiResponse(
        val legacy: Int = 0,
        val resolution: String = "4k",
        val stream_url: String,
        val stream_domains: List<String>,
    )

}
