package com.javtiful

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.contains("?")) {
            "$mainUrl/${request.data}&page=$page"
        } else {
            "$mainUrl/${request.data}?page=$page"
        }
        val document = app.get(url).document
        val home = document.select("article.front-video-card, div.front-video-card, div.card.border-0")
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
        val linkElem = this.selectFirst("a.front-video-thumb, a[href*='/video/'], a") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null

        val img = this.selectFirst("img")
        val altTitle = img?.attr("alt")?.removePrefix("Thumbnail for ")?.trim()
        val title = altTitle?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("div.front-video-card__title, h2, h3, h4")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: linkElem.attr("title").takeIf { it.isNotBlank() }
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
            val url = "$mainUrl/search?q=$encoded&page=$page"
            val document = app.get(url).document
            val results = document.select("article.front-video-card, div.front-video-card, div.card.border-0")
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

        val recommendations = document.select("article.front-video-card, div.front-video-card, div.card.border-0")
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
        val document = app.get(data).document

        // 1. Direct HTML5 source tags
        val sources = document.select("video source[src], source[src]")
        for (source in sources) {
            val src = source.attr("src")
            if (src.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = src,
                        type = INFER_TYPE
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // 2. Legacy AJAX CDN endpoint fallback
        runCatching {
            val token = document.selectFirst("#token_full")?.attr("data-csrf-token") ?: ""
            val script = document.selectFirst("script:containsData(vcpov)")?.data()
            val postid = script?.let { Regex("""vcpov\s*=\s*[`"'](.*?)['"`]""").find(it)?.groupValues?.get(1) } ?: ""
            if (postid.isNotBlank()) {
                val form = mapOf("video_id" to postid, "pid_c" to "", "token" to token)
                val m3u8 = app.post(
                    "$mainUrl/ajax/get_cdn",
                    data = form,
                    headers = mapOf("Referer" to data, "X-Requested-With" to "XMLHttpRequest")
                ).parsedSafe<Response>()?.playlists

                if (!m3u8.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = m3u8,
                            type = INFER_TYPE
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }

        // 3. Fallback to iframes
        document.select("iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (!src.contains("google") && !src.contains("facebook")) {
                runCatching {
                    loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        return true
    }

    data class Response(
        @JsonProperty("playlists_active")
        val playlistsActive: Long? = null,
        val playlists: String? = null,
        @JsonProperty("playlist_source")
        val playlistSource: String? = null,
    )
}