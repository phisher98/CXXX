// ! https://github.com/hexated/cloudstream-extensions-hexated/blob/master/GoodPorn/src/main/kotlin/com/hexated/GoodPorn.kt

package com.hexated

//import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import java.util.*

class GoodPorn : MainAPI() {
    override var mainUrl              = "https://goodporn.to"
    override var name                 = "GoodPorn"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=post_date&from="                            to "New Videos",
        "${mainUrl}/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=video_viewed&from="                         to "Most Viewed Videos",
        "${mainUrl}/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=rating&from="                               to "Top Rated Videos",
        "${mainUrl}/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=most_commented&from="                       to "Most Commented Videos",
        "${mainUrl}/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=duration&from="                             to "Longest Videos",
        "${mainUrl}/channels/brazzers/?mode=async&function=get_block&block_id=list_videos_common_videos_list&sort_by=post_date&from="          to "Brazzers",
        "${mainUrl}/channels/digitalplayground/?mode=async&function=get_block&block_id=list_videos_common_videos_list&sort_by=post_date&from=" to "Digital Playground",
        "${mainUrl}/channels/realitykings/?mode=async&function=get_block&block_id=list_videos_common_videos_list&sort_by=post_date&from="      to "Realitykings",
        "${mainUrl}/channels/babes-network/?mode=async&function=get_block&block_id=list_videos_common_videos_list&sort_by=post_date&from="     to "Babes Network",
        "${mainUrl}/categories/amateur/?mode=async&function=get_block&block_id=list_videos_common_videos_list&sort_by=post_date&from="         to "Amateur"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home     = document.select("div#list_videos_most_recent_videos_items div.item, div#list_videos_common_videos_list_items div.item").mapNotNull {
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

    private fun Element.toSearchResult(): SearchResponse? {
        val full_title = this.selectFirst("strong.title")?.text() ?: return null
        val last_index = full_title.lastIndexOf(" - ")
        val raw_title  = if (last_index != -1) full_title.substring(0, last_index) else full_title
        val title      = raw_title.removePrefix("- ").trim().removeSuffix("-").trim()

        val href       = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl  = fixUrlNull(this.select("div.img > img").attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (say in 1..15) {
            val document = app.get(
                "${mainUrl}/search/nikki-benz/?mode=async&function=get_block&block_id=list_videos_videos_list_search_result&q=${query}&category_ids=&sort_by=&from_videos=${say}&from_albums=${say}",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).document

            val results = document.select("div#list_videos_videos_list_search_result_items div.item").mapNotNull { it.toSearchResult() }
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

        document.select("div.info div:last-child a").map { res ->
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    res.attr("href").replace(Regex("\\?download\\S+.mp4&"), "?") + "&rnd=${Date().time}",
                    referer = data,
                    quality = Regex("([0-9]+p),").find(res.text())?.groupValues?.get(1).let { getQualityFromName(it) },
                    headers = mapOf("Range" to "bytes=0-"),
                )
            )
        }

        return true
    }
}
