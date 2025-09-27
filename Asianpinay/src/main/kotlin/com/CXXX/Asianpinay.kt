package com.CXXX

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class Asianpinay : MainAPI() {
    override var mainUrl              = "https://asianpinay.com"
    override var name                 = "Asianpinay"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "?filter=latest" to "Latest",
        "category/sexy-movies" to "Full Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/page/$page").document
        val home     = document.select("div.video-block").mapNotNull {
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
        val rawtitle      = this.selectFirst("span")?.text() ?: "Unknown"
        val title           = rawtitle.replaceFirstChar { it.uppercase() }
        val href       = this.selectFirst("a.thumb")!!.attr("href")
        val posterUrl  = this.select("a.thumb > img").attr("data-src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..2) {
            val document = app.get("${mainUrl}/?s=$query/page/$i").document
            val results = document.select("div.video-block").mapNotNull { it.toSearchResult() }
            searchResponse.addAll(results)

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val rawtitle        = document.selectFirst("section h1")?.text() ?: "Unknown"
        val title           = rawtitle.replaceFirstChar { it.uppercase() }
        val poster          = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val tags            = document.selectXpath("//div[contains(text(), 'Categories:')]/a").map { it.text() }
        val description     = document.selectFirst(".video-description > p:nth-child(2)")?.text()
        val actors          = document.selectXpath("//div[contains(text(), 'Models:')]/a").map { it.text() }
        val recommendations = document.select("div#list_videos_related_videos_items div.item").mapNotNull { it.toSearchResult() }
        return newMovieLoadResponse(title.trim(), url, TvType.NSFW, url) {
            this.posterUrl       = poster
            this.plot            = description
            this.tags            = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val href=document.selectFirst("meta[itemprop=embedURL]")?.attr("content")
        if (href!=null)
        {
            val doc= app.get(href, referer = mainUrl).text
            val video_id=Regex("video_id\\s*=\\s*['\"`](\\w+)['\"`];").find(doc)?.groupValues?.get(1).toString()
            val m3u8url=Regex("m3u8_loader_url\\s*=\\s*['\"`]([^'\"`]+)['\"`];").find(doc)?.groupValues?.get(1).toString()
            val regex = Regex("""^(?!.*//file).*?file:\s*["']([^"']*\.vtt)["']""", RegexOption.MULTILINE)
            val matches = regex.findAll(doc)
            for (match in matches) {
                val subtitle = match.groups[1]?.value.toString()
                if (subtitle.contains("subtitles"))
                {
                    subtitleCallback.invoke(
                        SubtitleFile(
                            "English",  // Use label for the name
                            subtitle     // Use extracted URL
                        )
                    )
                }
            }
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = "$m3u8url$video_id",
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }
}
