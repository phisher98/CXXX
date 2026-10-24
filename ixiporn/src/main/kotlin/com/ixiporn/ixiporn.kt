package com.ixiporn

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class ixiporn : MainAPI() {
    override var mainUrl = "https://ixiporn.live"
    override var name = "ixiporn"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "filter=latest" to "Latest Release",
        "filter=most" to "Most Viewed",
        "filter=popular" to "Top Rated",
        "filter=longest" to "Longest",
        "s=ullu" to "Ullu Web Series",
        "s=hunters" to "Hunter Web Series",
        "s=primeplay" to "Prime Play",
        "s=besharams" to "Besharams Web Series",
        "s=neonx" to "Neonx",
        "s=voovi" to "Voovi Web Series",
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a.infos") ?: return null
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = anchor.attr("title").takeIf { it.isNotBlank() } ?: anchor.selectFirst("span.title")?.text()
        if (title.isNullOrBlank()) return null
        val posterUrl = this.selectFirst("a.thumb img")?.let { img ->
            img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src")
        }

        return newMovieSearchResponse(fixTitle(title), href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/?${request.data}" else "$mainUrl/page/$page/?${request.data}"
        val document = app.get(url).document
        val home = document.select("div.video-block").mapNotNull { it.toSearchResult() }
        val hasNext = document.select("link[rel=next]").isNotEmpty() && home.isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
        val document = app.get(url).document
        val results = document.select("div.video-block").mapNotNull { it.toSearchResult() }
        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private fun decodePlayerSources(pageBody: String): List<String> {
        return Regex("""player-x\.php\?q=([A-Za-z0-9+/=]+)""").findAll(pageBody)
            .mapNotNull { match ->
                runCatching {
                    val decoded = Base64.decode(match.groupValues[1], Base64.DEFAULT).decodeToString()
                    val tag = URLDecoder.decode(decoded.substringAfter("tag=", ""), "UTF-8")
                    Regex("""<source\s+src="([^"]+)"""").findAll(tag).map { it.groupValues[1] }.toList()
                }.getOrNull()
            }.flatten().toList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val sources = decodePlayerSources(response.text)

        sources.forEach { src ->
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = src
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                    this.type = ExtractorLinkType.VIDEO
                }
            )
        }

        return sources.isNotEmpty()
    }
}
