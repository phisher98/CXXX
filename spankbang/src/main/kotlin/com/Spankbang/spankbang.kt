package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Spankbang : MainAPI() {
    override var mainUrl              = "https://spankbang.com"
    override var name                 = "Spankbang"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/trending_videos/" to "New Videos",
        "${mainUrl}/j2/channel/familyxxx/" to "Family XXX",
        "${mainUrl}/ce/channel/bratty+milf/" to "Bratty MILF",
        "${mainUrl}/cf/channel/bratty+sis/" to "Bratty Sis",
        "${mainUrl}/k5/channel/japan+hdv/" to "Japan HDV",
        "${mainUrl}/j3/channel/hot+wife+xxx/" to "Hot Wife XXX",
        "${mainUrl}/d6/channel/my+family+pies/" to "My Family Pies",
        "${mainUrl}/ho/channel/brazzers/" to "Brazzers",
        "${mainUrl}/6l/channel/mylf/" to "MYLF",
        "${mainUrl}/9n/channel/evil+angel/" to "Evil Angel",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}$page/"
        val document = app.get(url).document
        val home = document.select("div.video-item").mapNotNull { it.toSearchResult() }

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
        val href = fixUrlNull(this.selectFirst("a.thumb, a[href*='/video/']")?.attr("href")) ?: return null
        val titleText = this.selectFirst("a.thumb > picture > img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("a.thumb img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("a.n")?.text()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("a[title]")?.attr("title")?.takeIf { it.isNotBlank() }
            ?: return null
        val title = fixTitle(titleText).trim()

        val img = this.selectFirst("a.thumb img, img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-srcset")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encodedQuery = query.trim().replace(" ", "+")

        for (i in 1..4) {
            val document = app.get("${mainUrl}/s/$encodedQuery/$i/?o=all").document
            val results = document.select("div.video-item").mapNotNull { it.toSearchResult() }
            val newItems = results.filterNot { item -> searchResponse.any { it.url == item.url } }
            if (newItems.isEmpty()) break
            searchResponse.addAll(newItems)
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().substringBefore(" - SpankBang").trim()
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val tags = document.select("div.searches a, div.tags a, a[href*='/tag/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select("div.video-item")
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
        val html = document.html()

        // 1. Direct video source tags in HTML
        val sources = document.select("div#video_container video > source, video > source")
        for (source in sources) {
            val src = source.attr("src").takeIf { it.isNotBlank() } ?: continue
            val isM3u8 = src.contains(".m3u8") || source.attr("type").contains("mpegurl", ignoreCase = true)
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = fixUrl(src),
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        // 2. JavaScript m3u8 stream links
        val m3u8Urls = Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""").findAll(html)
            .map { it.groupValues[1] }
            .distinct()

        for (m3u8 in m3u8Urls) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        // 3. JavaScript stream_data mp4 qualities (e.g. '720p': ['https://...'])
        val mp4Matches = Regex("""['"](?:4k|1080p|720p|480p|320p|240p)['"]\s*:\s*\[\s*['"](https?://[^'"]+)['"]""").findAll(html)
        for (match in mp4Matches) {
            val streamUrl = match.groupValues[1]
            val quality = Regex("""(4k|1080|720|480|320|240)""").find(match.value)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Qualities.Unknown.value

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = quality
                }
            )
        }

        return true
    }
}