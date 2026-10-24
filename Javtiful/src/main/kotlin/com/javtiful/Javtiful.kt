package com.javtiful

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class Javtiful : MainAPI() {
    override var mainUrl = "https://javtiful.com"
    override var name = "Javtiful"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "uncensored" to "Uncensored",
        "videos?sort=being_watched" to "Being Watched",
        "videos?sort=most_viewed" to "Most Viewed",
        "videos?sort=top_favorites" to "Top Favorites",
    )

    private fun buildUrl(data: String, page: Int): String {
        return if (data.contains("?")) "$mainUrl/$data&page=$page" else "$mainUrl/$data?page=$page"
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(buildUrl(request.data, page)).document
        val home = document.select("article.video-card:not(.video-card--partner)")
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
        val linkElem = this.selectFirst("a.video-card__thumbnail, a[href*='/video/']") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null

        val img = linkElem.selectFirst("img") ?: this.selectFirst("img")
        val altTitle = img?.attr("alt")?.removePrefix("Thumbnail for ")?.trim()
        val title = altTitle?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("a.video-card__title")?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("a.video-card__title, h2, h3, h4")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val posterUrl = fixUrlNull(
            img?.attr("data-front-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.endsWith(".svg") }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encoded = query.trim().replace(" ", "+")

        for (page in 1..3) {
            val document = app.get("$mainUrl/search?q=$encoded&page=$page").document
            val results = document.select("article.video-card:not(.video-card--partner)")
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
            ?: document.title().substringBefore(" - Javtiful").trim()
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val tags = document.select("a[href*='/tag/'], a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = document.select("a[href*='/model/'], a[href*='/actress/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select("article.video-card:not(.video-card--partner)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
            this.tags = tags
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val body = app.get(data).text
        val endpoint = Regex(""""endpoint"\s*:\s*"(/video/\d+/playback)"""").find(body)?.groupValues?.get(1)
        val ticket = Regex(""""ticket"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
        if (endpoint == null || ticket == null) return false

        val response = runCatching {
            app.post(
                "$mainUrl$endpoint",
                data = mapOf("ticket" to ticket),
                headers = mapOf(
                    "Referer" to data,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).parsedSafe<PlaybackResponse>()
        }.getOrNull() ?: return false

        var found = false
        response.sources?.forEach { source ->
            if (source.src.isNullOrBlank()) return@forEach
            found = true
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = source.label?.takeIf { it.isNotBlank() } ?: name,
                    url = source.src,
                    type = INFER_TYPE
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = source.size ?: Qualities.Unknown.value
                }
            )
        }
        return found
    }

    data class PlaybackSource(
        val src: String? = null,
        val type: String? = null,
        val size: Int? = null,
        val label: String? = null,
    )

    data class PlaybackResponse(
        val success: Boolean? = null,
        val sources: List<PlaybackSource>? = null,
        @JsonProperty("defaultQuality")
        val defaultQuality: Int? = null,
    )
}
