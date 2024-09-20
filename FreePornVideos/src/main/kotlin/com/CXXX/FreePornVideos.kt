package com.CXXX

//import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class FreePornVideos : MainAPI() {
    override var mainUrl              = "https://www.freepornvideos.xxx"
    override var name                 = "Free Porn Videos"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "most-popular/week" to "Most Popular",
        "networks/brazzers-com" to "Brazzers",
        "networks/mylf-com" to "MYLF",
        "networks/brazzers-com" to "Brazzers",
        "networks/bangbros" to "BangBros",
        "networks/adult-time" to "Adult Time",
        "networks/rk-com" to "Reality Kings",
        "categories/jav-uncensored" to "Jav",
        "networks/mom-lover" to "MILF"

    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/${page+1}/").document
        val home     = document.select("#list_videos_common_videos_list_items > div.item").mapNotNull {
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
        val title      = this.select("strong.title").text()
        val href       = this.selectFirst("a")!!.attr("href")
        val posterUrl  = this.select("a img").attr("data-src")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }

    }

    fun String?.createSlug(): String? {
        return this?.filter { it.isWhitespace() || it.isLetterOrDigit() }
            ?.trim()
            ?.replace("\\s+".toRegex(), "-")
            ?.lowercase()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..5) {
            val searchquery=query.createSlug() ?:""
            val document = app.get(
                "${mainUrl}/search/$searchquery/")
            .document
            val results = document.select("#custom_list_videos_videos_list_search_result_items > div.item").mapNotNull { it.toSearchResult() }
            searchResponse.addAll(results)

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val full_title      = document.selectFirst("div.headline > h1")?.text()?.trim().toString()
        val last_index      = full_title.lastIndexOf(" - ")
        val raw_title       = if (last_index != -1) full_title.substring(0, last_index) else full_title
        val title           = raw_title.removePrefix("- ").trim().removeSuffix("-").trim()

        val poster          = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val tags            = document.selectXpath("//div[contains(text(), 'Categories:')]/a").map { it.text() }
        val description     = document.selectXpath("//div[contains(text(), 'Description:')]/em").text().trim()
        val actors          = document.selectXpath("//div[contains(text(), 'Models:')]/a").map { it.text() }
        val recommendations = document.select("div#list_videos_related_videos_items div.item").mapNotNull { it.toSearchResult() }

        val year            = full_title.substring(full_title.length - 4).toIntOrNull()
        val rating          = document.selectFirst("div.rating span")?.text()?.substringBefore("%")?.trim()?.toFloatOrNull()?.div(10)?.toString()?.toRatingInt()

        val raw_duration    = document.selectXpath("//span[contains(text(), 'Duration')]/em").text().trim()
        val duration_parts  = raw_duration.split(":")
        val duration        = when (duration_parts.size) {
            3 -> {
                val hours   = duration_parts[0].toIntOrNull() ?: 0
                val minutes = duration_parts[1].toIntOrNull() ?: 0

                hours * 60 + minutes
            }
            else -> {
                duration_parts[0].toIntOrNull() ?: 0
            }
        }

        return newMovieLoadResponse(title.removePrefix("- ").removeSuffix("-").trim(), url, TvType.NSFW, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.recommendations = recommendations
            this.rating          = rating
            this.duration        = duration
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        document.select("video source").map { res ->
            callback.invoke(
                ExtractorLink(
                    "FPV",
                    "FPV",
                    res.attr("src"),
                    referer = data,
                    quality = getQualityFromName(res.attr("label")),
                )
            )
        }

        return true
    }
}
