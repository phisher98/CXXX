package com.Javx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Javx : MainAPI() {
    override var name = "Javx"
    override var mainUrl = "https://javx.org"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = true
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "?filter=latest" to "Latest",
        "videos" to "Popular",
        "english-subtitles" to "English Subtitles",
        "category/milf" to "Milf",
        "category/cheating-wife" to "Cheating Wife",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            if (request.data.startsWith("?")) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/"
        } else {
            if (request.data.startsWith("?")) "$mainUrl/page/$page/${request.data}" else "$mainUrl/${request.data}/page/$page/"
        }
        val document = app.get(url).document
        val responseList = document.select("article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            HomePageList(request.name, responseList.distinctBy { it.url }, isHorizontalImages = true),
            hasNext = responseList.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = this.selectFirst("a[href*='/video/'], a") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null

        val title = linkElem.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("header span, h2, h3, a")?.text()?.takeIf { it.isNotBlank() }
            ?: return null

        val img = this.selectFirst("a img, img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            posterHeaders = mapOf("referer" to "$mainUrl/")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encoded = query.trim().replace(" ", "+")

        for (page in 1..3) {
            val document = app.get("$mainUrl/page/$page/?s=$encoded").document
            val results = document.select("article").mapNotNull { it.toSearchResult() }
            val unique = results.filterNot { item -> searchResponse.any { it.url == item.url } }
            if (unique.isEmpty()) break
            searchResponse.addAll(unique)
        }

        return searchResponse
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = query.trim().replace(" ", "+")
        val document = app.get("$mainUrl/page/$page/?s=$encoded").document
        val results = document.select("article").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newSearchResponseList(results, results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().substringBefore(" - JavX").trim()
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))

        val tags = document.select("a[href*='/tag/'], a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select("article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            posterHeaders = mapOf("referer" to "$mainUrl/")
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
        val doc = app.get(data).document

        // Check tabs
        doc.select("#sourcetabs a, a.btn-server").forEach {
            val href = fixUrlNull(it.attr("href")) ?: return@forEach
            runCatching {
                loadExtractor(href, "$mainUrl/", subtitleCallback, callback)
            }
        }

        // Check embedded iframes
        doc.select("iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (!src.contains("google") && !src.contains("facebook")) {
                runCatching {
                    loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        return true
    }
}

class StreamwishHG : StreamWishExtractor() {
    override val mainUrl = "https://hglink.to"
}