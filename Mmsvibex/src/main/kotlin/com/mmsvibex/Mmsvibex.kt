package com.mmsvibex

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Mmsvibex : MainAPI() {
    override var mainUrl = "https://mmsvibex.fit"
    override var name = "Mmsvibex"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // Each row is a WordPress listing that pages with /page/N/. "" is the site root (latest videos).
    override val mainPage = mainPageOf(
        "" to "Latest Videos",
        "category/indian/" to "Indian",
        "category/desi/" to "Desi",
        "category/others/" to "Others",
        "photos/" to "Photos",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // page 1 is the bare path; later pages are /<path>/page/N/
        val base = mainUrl.trimEnd('/') + "/" + request.data.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val url = if (page > 1) "${base}page/$page/" else base

        val document = app.get(url).document
        val videos = document.select("article.thumb-block.video-preview-item").mapNotNull { it.toSearchResult() }

        // keep loading while the page advertises a next page and still returns items
        val hasNext = videos.isNotEmpty() &&
            document.selectFirst("link[rel=next], a.next.page-numbers, .pagination a[href*=/page/]") != null

        return newHomePageResponse(request.name, videos, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/wp-json/mmsvibe/v1/search-suggest",
            params = mapOf("q" to query)
        )
        return response.parsed<List<Map<String, Any>>>().mapNotNull { item ->
            val title = item["title"] as? String ?: return@mapNotNull null
            val url = item["url"] as? String ?: return@mapNotNull null
            val poster = item["thumbnail"] as? String
            newMovieSearchResponse(title, url, TvType.NSFW) { posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() ?: "Unknown"
        val poster = document.selectFirst(".post-thumbnail img")?.attr("src")
        val plot = document.selectFirst(".entry-content p")?.text()
        val tags = document.select(".tag-link").map { it.text() }

        val episodes = document.select("a.mms-image-album-item").mapIndexed { index, element ->
            newEpisode(element.attr("href").takeIf { it.isNotEmpty() } ?: url) {
                name = element.attr("title").takeIf { it.isNotEmpty() } ?: "Episode ${index + 1}"
                season = 1
                episode = index + 1
                posterUrl = element.selectFirst("img")?.attr("src")
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val videoLinks = document.select("iframe").mapNotNull { it.attr("src").takeIf { s -> s.isNotEmpty() } } +
            document.select("video source").mapNotNull { it.attr("src").takeIf { s -> s.isNotEmpty() } } +
            document.select("[src*='cdn.mmsvibex.fit']").mapNotNull { it.attr("src").takeIf { s -> s.isNotEmpty() } }

        videoLinks.forEach { link ->
            if (link.contains("mmsvibex.fit")) {
                callback(
                    newExtractorLink(name, name, link,
                        if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return videoLinks.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".title")?.text()?.takeIf { it.isNotBlank() } ?: return null
        val url = selectFirst("a")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        // thumbnails are lazy-loaded, so prefer the real source attributes over a placeholder src
        val img = selectFirst("img")
        val posterUrl = img?.let {
            it.attr("data-src").ifBlank { it.attr("data-original").ifBlank { it.attr("data-lazy-src").ifBlank { it.attr("src") } } }
        }?.takeIf { it.isNotBlank() }
        return newMovieSearchResponse(title, url, TvType.NSFW) { this.posterUrl = posterUrl }
    }
}
