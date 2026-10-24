package com.JAVHd

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class JAVHDProvider : MainAPI() {
    override var mainUrl              = "https://javhd.today"
    override var name                 = "JAV HD"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded
    private val subtitleCatUrl        = "https://www.subtitlecat.com"

    override val mainPage = mainPageOf(
        "/releaseday/" to "Release Day",
        "/recent/" to "Latest Updates",
        "/popular/today/" to "Most Viewed Today",
        "/popular/week/" to "Most Viewed Week",
        "/jav-sub/" to "Jav Subbed",
        "/jav-sub/popular/year/" to "Most Viewed Jav Subbed",
        "/uncensored-jav/" to "Uncensored",
        "/reducing-mosaic/" to "Reduced Mosaic",
        "/amateur/" to "Amateur"
    )

    private fun ajaxCards(raw: String): List<SearchResponse> {
        return runCatching {
            val html = JsonParser.parseString(raw).asJsonObject.get("html")?.asString ?: return emptyList()
            Jsoup.parse(html).select("div.video").mapNotNull { it.toSearchResult() }
        }.getOrNull() ?: emptyList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when (request.data) {
            "/releaseday/", "/recent/" -> "$mainUrl${request.data}?ajax=browse_videos&page=$page"
            "/jav-sub/", "/uncensored-jav/", "/reducing-mosaic/", "/amateur/" ->
                if (page == 1) "$mainUrl${request.data}?ajax=1"
                else "$mainUrl${request.data}recent/$page?ajax=1"
            "/jav-sub/popular/year/" ->
                if (page == 1) "$mainUrl${request.data}?ajax=1"
                else "$mainUrl${request.data}$page?ajax=1"
            else -> "$mainUrl${request.data}?ajax=browse_videos&page=$page"
        }
        val responseList = ajaxCards(app.get(url).text)
        return newHomePageResponse(
            HomePageList(request.name, responseList.distinctBy { it.url }, isHorizontalImages = false),
            hasNext = responseList.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = this.selectFirst(".thumbnail, a[href*='/video/'], a") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null
        val title = this.selectFirst(".video-title, h3, h2")?.text()?.takeIf { it.isNotBlank() }
            ?: linkElem.attr("title").takeIf { it.isNotBlank() }
            ?: return null

        val img = this.selectFirst(".video-thumb img, img")
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
        val encodedQuery = query.trim().replace(" ", "+")
        for (page in 1..3) {
            val results = ajaxCards(app.get("$mainUrl/search/video/?s=$encodedQuery&page=$page&ajax=1").text)
            val unique = results.filterNot { item -> searchResponse.any { it.url == item.url } }
            if (unique.isEmpty()) break
            searchResponse.addAll(unique)
        }
        return searchResponse
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = query.trim().replace(" ", "+")
        val results = ajaxCards(app.get("$mainUrl/search/video/?s=$encodedQuery&page=$page&ajax=1").text)
            .distinctBy { it.url }
        return newSearchResponseList(results, results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().substringBefore(" - JAV").trim()
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val tags = document.select(".video-tags a, a[href*='/tag/'], a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = document.select(".video-actors a, a[href*='/actress/'], a[href*='/star/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select("div.video")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        runAllAsync(
            {
                val episodeList = doc.select(".button_style .button_choice_server")
                episodeList.forEach { item ->
                    val link = item.attr("data-embed")
                    if (link.isNotBlank()) {
                        runCatching {
                            val decoded = if (link.startsWith("http")) link else base64Decode(link)
                            loadExtractor(decoded, "$mainUrl/", subtitleCallback, callback)
                        }
                    }
                }

                // Fallback to iframes if no button server found
                doc.select("iframe[src]").forEach { iframe ->
                    val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
                    if (!src.contains("google") && !src.contains("facebook")) {
                        runCatching {
                            loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                        }
                    }
                }
            },
            {
                getExternalSubtitile(doc, subtitleCallback)
            }
        )

        return true
    }

    private suspend fun getExternalSubtitile(doc: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: doc.title()
            val javCode = "([a-zA-Z]+-\\d+)".toRegex().find(title)?.groups?.get(1)?.value
            if (!javCode.isNullOrEmpty()) {
                val query = "$subtitleCatUrl/index.php?search=$javCode"
                val subDoc = app.get(query, timeout = 15).document
                val subList = subDoc.select("td a")
                for (item in subList) {
                    if (item.text().contains(javCode, ignoreCase = true)) {
                        val fullUrl = "$subtitleCatUrl/${item.attr("href")}"
                        val pDoc = app.get(fullUrl, timeout = 10).document
                        val sList = pDoc.select(".col-md-6.col-lg-4")
                        for (subItem in sList) {
                            try {
                                val language = subItem.select(".sub-single span:nth-child(2)").text()
                                val text = subItem.select(".sub-single span:nth-child(3) a")
                                if (text.isNotEmpty() && text[0].text() == "Download") {
                                    val url = "$subtitleCatUrl${text[0].attr("href")}"
                                    subtitleCallback.invoke(
                                        newSubtitleFile(
                                            language.replace("\uD83D\uDC4D \uD83D\uDC4E", "").trim(),
                                            url
                                        )
                                    )
                                }
                            } catch (_: Exception) { }
                        }
                    }
                }
            }
        } catch (_: Exception) { }
    }
}
