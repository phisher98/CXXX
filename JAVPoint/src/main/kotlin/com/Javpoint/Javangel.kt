package com.Javpoint

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Javangel : MainAPI() {
    override var mainUrl              = "https://jav-angel.net"
    override var name                 = "Javangel"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "tag/uncen-leaked" to "Uncen Leaked",
        "tag/english-sub" to "English Sub",
        "tag/vr" to "VR",
        "category/uncensored" to "Uncensored",
        "tag/re-upload" to "Old Jav",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        val document = app.get(url).document
        val home = document.select("div.tdb_module_loop > div, div.td-module-thumb, div.td_module_wrap")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home.distinctBy { it.url },
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = this.selectFirst("h3 a, a[href*='jav-angel'], a") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null
        val title = linkElem.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("h3, h2, a")?.text()
            ?: return null

        val spanImg = this.selectFirst("span.entry-thumb, span[data-img-url]")?.attr("data-img-url")
        val imgTag = this.selectFirst("img")?.attr("src")
        val posterUrl = fixUrlNull(spanImg?.takeIf { it.isNotBlank() } ?: imgTag)

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encoded = query.trim().replace(" ", "+")

        for (i in 1..3) {
            val url = if (i == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$i/?s=$encoded"
            val document = app.get(url).document
            val results = document.select("div.td-module-thumb, div.tdb_module_loop > div, div.td_module_wrap")
                .mapNotNull { it.toSearchResult() }
            val unique = results.filterNot { item -> searchResponse.any { it.url == item.url } }
            if (unique.isEmpty()) break
            searchResponse.addAll(unique)
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().substringBefore(" - Jav-Angel").trim()
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val tags = document.select("div.td-post-source-tags a, a[href*='/tag/'], a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select("div.tdb_module_loop > div, div.td-module-thumb, div.td_module_wrap")
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
        val iframes = document.select("iframe[src]")

        for (iframe in iframes) {
            val src = fixUrlNull(iframe.attr("src")) ?: continue
            if (src.contains("google") || src.contains("facebook")) continue
            runCatching {
                loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
            }
        }
        return true
    }
}
