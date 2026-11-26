package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class IncestFlix : MainAPI() {
    override var mainUrl = "https://incestflix.com.co"
    override var name = "IncestFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val ua =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
    private val posterHeaders get() = mapOf("Referer" to "$mainUrl/", "User-Agent" to ua)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/stepmom" to "Stepmom",
        "category/stepsister" to "Stepsister",
        "category/stepdaughter" to "Stepdaughter",
        "category/milf" to "MILF",
        "category/freeuse" to "Freeuse",
    )

    private suspend fun getDoc(url: String): Document =
        app.get(url, headers = mapOf("User-Agent" to ua), referer = "$mainUrl/").document

    private fun Document.hasNextPage(page: Int): Boolean =
        selectFirst("a[href*=/page/${page + 1}/]") != null

    private fun Document.videoCards(): List<SearchResponse> =
        select("a.video").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = if (request.data.isBlank()) mainUrl else "$mainUrl/${request.data}"
        val url = if (page <= 1) "$base/" else "$base/page/$page/"
        val document = getDoc(url)

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = document.videoCards(), isHorizontalImages = true),
            hasNext = document.hasNextPage(page)
        )
    }

    // Card markup: <a class="video" style="background-image: url('...')" title="..." href="..."><h2 class="vtitle">...</h2></a>
    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href"))?.takeIf { it.startsWith(mainUrl) } ?: return null
        val title = selectFirst("h2.vtitle")?.text()?.takeIf { it.isNotBlank() }
            ?: attr("title").takeIf { it.isNotBlank() }
            ?: return null
        val poster = Regex("""url\(['"]?(.*?)['"]?\)""").find(attr("style"))?.groupValues?.get(1)

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = this@IncestFlix.posterHeaders
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim().replace(Regex("\\s+"), "+")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        val document = getDoc(url)
        return newSearchResponseList(document.videoCards(), document.hasNextPage(page))
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url)
        val title = (document.selectFirst("meta[property=og:title]")?.attr("content") ?: document.title())
            .substringBeforeLast(" - IncestFlix")
            .trim()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("video")?.attr("poster")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val tags = document.select("a.cat, a.tag").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val actors = document.select(".model-list a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val recommendations = document.videoCards().filter { it.url != url }

        return newMovieLoadResponse(title.ifBlank { name }, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = this@IncestFlix.posterHeaders
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDoc(data)

        // Player: <video id="my-video"><source src="https://cdn.../ID_480p_wm.mp4"></video>
        val links = linkedSetOf<String>()
        document.select("video source[src], video[src]").forEach { links.add(fixUrl(it.attr("src"))) }
        if (links.isEmpty()) {
            Regex("""https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*""")
                .findAll(document.html())
                .forEach { links.add(it.value) }
        }

        if (links.isEmpty()) {
            val iframes = document.select("iframe[src]").map { fixUrl(it.attr("src")) }
            iframes.forEach { loadExtractor(it, data, subtitleCallback, callback) }
            return iframes.isNotEmpty()
        }

        links.forEach { link ->
            val quality = Regex("""(\d{3,4})p""").find(link)?.groupValues?.get(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = quality
                    this.headers = mapOf("User-Agent" to ua)
                }
            )
        }
        return true
    }
}
