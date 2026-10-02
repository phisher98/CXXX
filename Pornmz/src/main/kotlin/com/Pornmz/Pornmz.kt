package com.Pornmz

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Pornmz : MainAPI() {
    override var mainUrl              = "https://pornmz.com"
    override var name                 = "Pornmz"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "Home",
        "/pmvideo/s/brazzers" to "Brazzers",
        "/pmvideo/s/bangbros" to "BangBros",
        "/pmvideo/s/naughtyamerica" to "NaughtyAmerica",
        "/pmvideo/s/realitykings" to "RealityKings",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isEmpty()) {
            "$mainUrl/page/$page/"
        } else {
            "$mainUrl${request.data}/page/$page/"
        }
        val document = app.get(url).document
        val home = document.select(".videos-list a").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name    = request.name,
                list    = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title").takeIf { it.isNotBlank() } ?: return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        var posterUrl = this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("data-src")
            ?: this.selectFirst("video")?.attr("poster")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encoded = query.trim().replace(" ", "+")

        for (i in 1..5) {
            val document = app.get("$mainUrl/page/$i/?s=$encoded").document
            val results = document.select(".videos-list a").mapNotNull { it.toSearchResult() }

            if (results.isEmpty()) break
            searchResponse.addAll(results)
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title       = document.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        val poster      = document.selectFirst("meta[property='og:image']")?.attr("content") ?: ""
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val docText  = document.toString()

        // 1) Direct JS variable extraction
        val jsRegex = Regex("""(?:video_url|file|src)\s*[:=]\s*['"]?(https?://[^'",\s>]+\.mp4[^'",\s>]*)['"]?""")
        val jsLinks = jsRegex.findAll(docText).map { it.groupValues[1] }.distinct().toList()
        for (link in jsLinks) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = link
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        // 2) Try iframes
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (iframeSrc.isNotBlank()) {
                runCatching { loadExtractor(iframeSrc, data, subtitleCallback, callback) }
            }
        }

        // 3) Direct source tags
        document.select("source[src]").forEach { source ->
            val src = source.attr("src")
            if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8"))) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = this.name,
                        url    = src
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }
}
