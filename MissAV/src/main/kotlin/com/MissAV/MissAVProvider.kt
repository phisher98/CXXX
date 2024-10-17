package com.MissAv

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.FormBody

class MissAVProvider : MainAPI() {
    override var mainUrl              = "https://missav.com"
    override var name                 = "MissAV"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
            "/dm513/en/new" to "Recent Update",
            "/dm509/en/release" to "New Releases",
            "/dm561/en/uncensored-leak" to "Uncensored Leak",
            "/dm242/en/today-hot" to "Most Viewed Today",
            "/dm168/en/weekly-hot" to "Most Viewed by Week",
            "/dm207/en/monthly-hot" to "Most Viewed by Month"
        )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            val document = app.get("$mainUrl${request.data}?page=$page").document
            val responseList  = document.select(".thumbnail").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = true),hasNext = true)

    }

    private fun Element.toSearchResult(): SearchResponse {
        val status = this.select(".bg-blue-800").text()
        val title = if(!status.isNullOrBlank()){"[$status] "+ this.select(".text-secondary").text()} else {this.select(".text-secondary").text()}
        val href = this.select(".text-secondary").attr("href")
        val posterUrl = this.selectFirst(".w-full")?.attr("data-src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..5) {
            val document = app.get("$mainUrl/en/search/$query?page=$i").document
            //val document = app.get("${mainUrl}/page/$i/?s=$query").document

            val results = document.select(".thumbnail").mapNotNull { it.toSearchResult() }

            if(!results.isNullOrEmpty())
            {
                for (result in results)
                {
                    if(!searchResponse.contains(result))
                    {
                        searchResponse.add(result)
                    }
                }
            }
            else
            {
                break
            }
            /*if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }

            if (results.isEmpty()) break*/
        }

        return searchResponse

    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString().replace("| PornHoarder.tv","")
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).document
        with(app.get(data)) {
            getAndUnpack(this.text).let { unpackedText ->
                val linkList = unpackedText.split(";")
                val finalLink = "source='(.*)'".toRegex().find(linkList.first())?.groups?.get(1)?.value
                callback.invoke(ExtractorLink(
                    name,
                    name,
                    finalLink.toString(),
                    "",
                    Qualities.Unknown.value,
                    isM3u8 = true
                ))
            }
        }


        return true
    }
}
