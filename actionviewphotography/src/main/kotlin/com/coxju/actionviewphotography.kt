package com.coxju

import com.lagradost.api.Log
import org.json.JSONObject
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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

        return newMovieSearchResponse(title, href, TvType.Movie) {
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
        Log.d("Phisher",data)
        val script = document.selectFirst("script:containsData(window.playlist)")
        if (script != null) {
            val jsonString = script.data()
                .substringAfter("window.playlist = ")
                .substringBefore(";")
            val jsonObject = JSONObject(jsonString)
            Log.d("Phisher",jsonObject.toString())
            val sources = jsonObject.getJSONArray("sources")
            Log.d("Phisher",sources.toString())
            val extlinkList = mutableListOf<ExtractorLink>()

            for (i in 0 until sources.length()) {
                val source = sources.getJSONObject(i)
                Log.d("Phisher",source.toString())
                extlinkList.add(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = httpsify( source.getString("file")),
                        referer = "",
                        quality = getQualityFromName(source.getString("label"))
                    )
                )
            }
            extlinkList.forEach(callback)
        }
        return true
    }
}
