package com.aagmaal

import com.google.gson.JsonParser
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Aagmaal : MainAPI() {
    override var mainUrl = "https://aagmaall.com"
    override var name = "Aagmaal"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/ullu-originals-web-series" to "Ullu",
        "tag/moodx" to "MoodX",
        "tag/hunters" to "Hunters",
        "tag/primeplay" to "PrimePlay",
        "tag/kooku" to "Kooku",
        "tag/besharams" to "Besharams",
        "tag/neonx" to "NeonX",
    )

    private fun toResult(post: Element): SearchResponse? {
        val anchor = post.selectFirst("a") ?: return null
        val url = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = anchor.attr("title").takeIf { it.isNotBlank() } ?: anchor.text()
        if (title.isBlank()) return null
        val img = post.selectFirst("img")
        val imageUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

        return newMovieSearchResponse(fixTitle(title), url, TvType.NSFW) {
            this.posterUrl = fixUrlNull(imageUrl)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.isEmpty() && page == 1 -> "$mainUrl/"
            request.data.isEmpty() -> "$mainUrl/page/$page/"
            page == 1 -> "$mainUrl/${request.data}/"
            else -> "$mainUrl/${request.data}/page/$page/"
        }
        val document = app.get(url).document
        val home = document.select("article.loop-video").mapNotNull { toResult(it) }
        val hasNext = document.select("link[rel=next]").isNotEmpty() && home.isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, home, true), hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.get("$mainUrl/page/$page/?s=$query").document
        val searchResult: List<SearchResponse> =
            document.select("article.loop-video").mapNotNull { toResult(it) }
        return searchResult.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val streamLink = doc.selectFirst("iframe[data-litespeed-src]")?.attr("data-litespeed-src")
            ?: doc.selectFirst("iframe[src]")?.attr("src") ?: ""

        return newMovieLoadResponse(title, url, TvType.NSFW, streamLink.ifEmpty { url }) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private suspend fun extractVidhost(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val host = embedUrl.substringAfter("//").substringBefore("/")
            val filecode = embedUrl.trimEnd('/').substringAfterLast('/')
            val response = app.post(
                "https://$host/api/stream",
                json = mapOf(
                    "filecode" to filecode,
                    "device" to "web",
                    "codecs" to emptyList<String>()
                ),
                referer = embedUrl
            ).text
            val stream = JsonParser.parseString(response).asJsonObject
                .get("streaming_url")?.asString

            if (stream.isNullOrEmpty()) return false

            callback.invoke(
                newExtractorLink(name, name, stream) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                    this.type = ExtractorLinkType.M3U8
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isEmpty()) return false

        val host = data.substringAfter("//").substringBefore("/")
        if (host.contains("vidhost")) {
            if (extractVidhost(data, subtitleCallback, callback)) return true
        }

        return loadExtractor(data, referer = data, subtitleCallback, callback)
    }
}
