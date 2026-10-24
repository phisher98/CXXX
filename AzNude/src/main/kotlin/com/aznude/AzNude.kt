package com.aznude

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class AzNude : MainAPI() {
    override var mainUrl = "https://www.aznude.com"
    override var name = "AZNude"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "browse/videos/trending" to "Trending Videos",
        "browse/videos/recent" to "Recent Videos",
        "browse/videos/popular" to "Popular Videos",
        "browse/videos/duration/long" to "Long Videos",
        "browse/videos/sortedceleb" to "By Celeb",
        "browse/celebs/trending" to "Trending Celebs",
    )

    private fun Element.toVideoResult(): SearchResponse? {
        val anchor = this.selectFirst("a[href*=/azncdn/]") ?: return null
        val url = fixUrl(anchor.attr("href"))
        val info = this.selectFirst("div.video-info")
        val movieTitle = info?.selectFirst("a.video-title")?.text()
        val celeb = info?.selectFirst("div.video-celebs a")?.attr("title")
        val title = when {
            !celeb.isNullOrBlank() && !movieTitle.isNullOrBlank() -> "$celeb in $movieTitle"
            !movieTitle.isNullOrBlank() -> movieTitle
            else -> anchor.selectFirst("img")?.attr("alt") ?: return null
        }
        val posterUrl = anchor.selectFirst("img")?.attr("src") ?: anchor.attr("data-thumb")

        return newMovieSearchResponse(fixTitle(title), url, TvType.NSFW) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }

    private fun Element.toCelebResult(): SearchResponse? {
        val anchor = this.selectFirst("a[href*=/view/celeb/]") ?: return null
        val url = fixUrl(anchor.attr("href"))
        val title = this.selectFirst("div.vertical-list__title")?.text() ?: return null
        val posterUrl = anchor.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }

    private fun String.toEmbedUrl(): String {
        val slug = this.trimEnd('/').substringAfterLast('/').removeSuffix(".html")
        return "$mainUrl/embed/$slug.html"
    }

    private suspend fun fetchPoster(url: String): String? {
        return runCatching {
            val doc = app.get(url, referer = mainUrl).document
            doc.selectFirst("link[rel=preload][as=image]")?.attr("href")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")
                ?: doc.selectFirst("img.single-page-banner__image")?.attr("src")
        }.getOrNull()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}/$page.html"
        val document = app.get(url, referer = mainUrl).document

        val home = if (request.data.startsWith("browse/celebs")) {
            document.select("div.vertical-list__item").mapNotNull { it.toCelebResult() }
        } else {
            document.select("div.media-list-item.video-list-item").mapNotNull { it.toVideoResult() }
        }

        val maxPage = document.select("ul.pagination-section a.section-page-link[href]")
            .mapNotNull { it.text().trim().toIntOrNull() }.maxOrNull() ?: page
        val hasNext = home.isNotEmpty() && page < maxPage

        return newHomePageResponse(HomePageList(request.name, home, true), hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return emptyList<SearchResponse>().toNewSearchResponseList()
        val results = mutableListOf<SearchResponse>()

        runCatching {
            val tag = query.trim().lowercase().replace(" ", "")
            if (tag.isNotEmpty()) {
                val tagDoc = app.get("$mainUrl/tags/vids/$tag/1.html", referer = mainUrl).document
                tagDoc.select("div.media-list-item.video-list-item")
                    .mapNotNull { it.toVideoResult() }
                    .forEach { results.add(it) }
            }
        }

        val document = app.get(
            "https://html.duckduckgo.com/html/?q=site%3Aaznude.com+$query",
            referer = "https://duckduckgo.com/"
        ).document

        val targets = document.select("a.result__a").mapNotNull { anchor ->
            val href = anchor.attr("href")
            val target = href.substringAfter("uddg=", "").takeIf { it.isNotEmpty() }
                ?.let { URLDecoder.decode(it.substringBefore("&"), "UTF-8") } ?: return@mapNotNull null
            if (!target.contains("aznude.com")) return@mapNotNull null
            if (!target.contains("/view/celeb/") && !target.contains("/view/movie/") &&
                !target.contains("/azncdn/")
            ) return@mapNotNull null

            val title = anchor.text().removeSuffix(" - AZNude").removeSuffix(" Nude")
            Pair(title, target)
        }.distinctBy { it.second }.take(10)

        targets.amap { (title, target) ->
            val poster = fetchPoster(target)

            newMovieSearchResponse(title, target, TvType.NSFW) {
                this.posterUrl = fixUrlNull(poster)
            }
        }.forEach { results.add(it) }

        return results.distinctBy { it.url }.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document

        if (url.contains("/azncdn/")) {
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
            val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            val poster = doc.selectFirst("link[rel=preload][as=image]")?.attr("href")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

            return newMovieLoadResponse(title, url, TvType.NSFW, url.toEmbedUrl()) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        }

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("title")?.text()?.removeSuffix(" - AZNude") ?: "$name"
        val poster = doc.selectFirst("link[rel=preload][as=image]")?.attr("href")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")
            ?: doc.selectFirst("img.single-page-banner__image")?.attr("src")
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val episodes = doc.select("div.media-list-item.video-list-item").mapNotNull { item ->
            val anchor = item.selectFirst("a[href*=/azncdn/]") ?: return@mapNotNull null
            val clipUrl = fixUrl(anchor.attr("href"))
            val info = item.selectFirst("div.video-info")
            val movieTitle = info?.selectFirst("a.video-title")?.text()
            val celeb = info?.selectFirst("div.video-celebs a")?.attr("title")
            val epTitle = when {
                !celeb.isNullOrBlank() && !movieTitle.isNullOrBlank() -> "$celeb in $movieTitle"
                else -> anchor.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            }
            val epPoster = anchor.selectFirst("img")?.attr("src") ?: anchor.attr("data-thumb")

            newEpisode(clipUrl.toEmbedUrl()) {
                this.name = fixTitle(epTitle)
                this.posterUrl = fixUrlNull(epPoster)
            }
        }
        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val body = app.get(data, referer = mainUrl).text
        val sourceRegex = Regex("""\{\s*file:\s*"([^"]+)"(?:\s*,\s*label:\s*"([^"]*)")?""")

        var found = false
        sourceRegex.findAll(body).forEach { match ->
            val file = match.groupValues[1]
            val label = match.groupValues[2]
            if (file.endsWith(".json")) return@forEach
            found = true

            val (quality, type) = when (label) {
                "HD" -> Qualities.P1080.value to ExtractorLinkType.VIDEO
                "HQ" -> Qualities.P720.value to ExtractorLinkType.VIDEO
                "LQ" -> Qualities.P360.value to ExtractorLinkType.VIDEO
                else -> Qualities.Unknown.value to ExtractorLinkType.M3U8
            }
            callback.invoke(
                newExtractorLink(name, label.ifEmpty { name }, file) {
                    this.referer = mainUrl
                    this.quality = quality
                    this.type = type
                }
            )
        }
        return found
    }
}
