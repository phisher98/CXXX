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
        val document = app.get(data, referer = "$mainUrl/").document
        val found = linkedSetOf<String>()

        // 1) Schema.org <meta itemprop="contentUrl"> — the site now stores the HLS (video.twimg.com) url here
        document.select("meta[itemprop=contentUrl]").forEach { meta ->
            meta.attr("content").takeIf { it.startsWith("http") }?.let { found.add(it) }
        }

        // 2) Internal player iframe (/wp-content/plugins/clean-tube-player/public/player-x.php?q=...)
        //    which contains a plain <video><source src=...m3u8></video>
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val iframeSrc = fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") }) ?: return@forEach
            if (iframeSrc.contains("pornmz", true) || iframeSrc.contains("player-x.php")) {
                runCatching {
                    val playerDoc = app.get(iframeSrc, referer = data).document
                    playerDoc.select("video source[src], video[src]").forEach { el ->
                        fixUrlNull(el.attr("src"))?.let { found.add(it) }
                    }
                }
            } else {
                runCatching { loadExtractor(iframeSrc, data, subtitleCallback, callback) }
            }
        }

        // 3) Direct <source> tags / js variables on the page itself (older posts)
        document.select("video source[src]").forEach { el -> fixUrlNull(el.attr("src"))?.let { found.add(it) } }
        Regex("""(?:video_url|file)\s*[:=]\s*['"](https?://[^'"\s]+\.(?:mp4|m3u8)[^'"\s]*)['"]""")
            .findAll(document.html()).forEach { found.add(it.groupValues[1]) }

        found.filter { it.contains(".m3u8") || it.contains(".mp4") }.forEach { link ->
            val isHls = link.contains(".m3u8")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = link,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    // twimg rejects foreign referers, so only send one for the site's own CDN
                    this.referer = if (link.contains("twimg.com")) "" else "$mainUrl/"
                    this.quality = Regex("""(\d{3,4})p""").find(link)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Qualities.Unknown.value
                }
            )
        }

        return found.isNotEmpty()
    }
}
