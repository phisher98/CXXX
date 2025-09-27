package com.coxju

import com.lagradost.api.Log
import org.json.JSONObject
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.Headers
import okhttp3.Interceptor

class actionviewphotography : MainAPI() {
    override var mainUrl              = "https://ukdevilz.com"
    override var name                 = "Noodle NSFW"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "video/milf" to "Milf",
        "video/brattysis" to "Brattysis",
        "video/web%20series" to "Web Series",
        "video/japanese" to "Japanese",
        "video/Step" to "Step category",
        "/video/tiktok" to "TikTok",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}?p=$page").document
        val home     = document.select("#list_videos > div.item").mapNotNull { it.toSearchResult() }

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
        val title     = fixTitle(this.select("div.i_info > div.title").text())
        val href      = fixUrl(this.select("a").attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("a >div> img")?.attr("data-src")!!.trim())

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..5) {
            val document = app.get("${mainUrl}/video/$query?p=$i").document
            val results = document.select("#list_videos > div.item").mapNotNull { it.toSearchResult() }
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
        val poster      = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content").toString())
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val script = document.selectFirst("script:containsData(window.playlist)")
        if (script != null) {
            val jsonString = script.data()
                .substringAfter("window.playlist = ")
                .substringBefore(";")
            val jsonObject = JSONObject(jsonString)
            val sources = jsonObject.getJSONArray("sources")
            val extlinkList = mutableListOf<ExtractorLink>()
            val headers = mapOf(
                "Accept" to "*/*",
                "Sec-Fetch-Dest" to "video",
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "cross-site",
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            )

            for (i in 0 until sources.length()) {
                val source = sources.getJSONObject(i)
                extlinkList.add(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = httpsify(source.getString("file")),
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName(source.getString("label"))
                        this.headers = headers
                    }
                )
            }
            extlinkList.forEach(callback)
        }
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()

            val modifiedRequest = request.newBuilder()
                .headers(Headers.Builder().build()) // Clear all headers
                .header("User-Agent", "stagefright/1.2 (Linux;Android 13)")
                .build()

            chain.proceed(modifiedRequest)
        }
    }
}
