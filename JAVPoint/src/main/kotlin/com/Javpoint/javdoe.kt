package com.Javpoint

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Javdoe : MainAPI() {
    override var mainUrl              = "https://javdoe.sh"
    override var name                 = "Javdoe"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "recent" to "Latest",
        "releaseday" to "New Release",
        "english-subtitle" to "English Subtitle",
        "asian" to "Asian",
        "tag/uncensored" to "Uncensored",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/$page/"
        val document = app.get(url).document
        val home = document.select("ul.videos > li")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home.distinctBy { it.url },
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = this.selectFirst("div.video > a, a[href*='/video/'], a") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null
        val title = linkElem.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("span.title, h3, h2")?.text()
            ?: return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encoded = query.trim().replace(" ", "+")

        for (i in 1..3) {
            val document = app.get("$mainUrl/search/video/?s=$encoded&page=$i").document
            val results = document.select("ul.videos > li").mapNotNull { it.toSearchResult() }
            val unique = results.filterNot { item -> searchResponse.any { it.url == item.url } }
            if (unique.isEmpty()) break
            searchResponse.addAll(unique)
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().substringBefore(" - JavDoe").trim()
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val tags = document.select("div.tags a, a[href*='/tag/'], a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select("ul.videos.related > li, ul.videos > li")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
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
        val sourcelist = mutableListOf<String>()

        val onclickValue = document.selectFirst(".button_choice_server")?.attr("onclick")
        if (!onclickValue.isNullOrBlank()) {
            val playEmbedContent = Regex("'(https?://[^']+)'").find(onclickValue)?.groupValues?.getOrNull(1)
                ?: Regex("'(.*?)'").find(onclickValue)?.groupValues?.getOrNull(1)

            if (!playEmbedContent.isNullOrBlank()) {
                runCatching {
                    val embedUrl = fixUrl(playEmbedContent)
                    val sources = app.get(embedUrl).document
                    val liElements = sources.select("li.button_choice_server")
                    for (liElement in liElements) {
                        val oc = liElement.attr("onclick")
                        val link = oc.substringAfter("playEmbed('", "").substringBefore("')", "")
                        if (link.isNotBlank()) {
                            sourcelist.add(link)
                        }
                    }
                }
            }
        }

        // Direct iframes in page
        document.select("iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (!src.contains("google") && !src.contains("facebook")) {
                sourcelist.add(src)
            }
        }

        sourcelist.distinct().forEach { link ->
            runCatching {
                loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
            }
        }
        return true
    }
}
