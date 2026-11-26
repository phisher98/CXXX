package com.Aflamk1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * aflamk1.net — Arabic-subtitled tube on the KVS platform.
 * Cards:   div.item > a[href][title] > div.img > img.thumb[data-original]
 * Player:  flashvars video_url / video_alt_url (+ *_text quality labels) -> /get_file/... mp4
 * Paging:  /<section>/<n>/ for lists, /search/<q>/?from_videos=<n> for search.
 */
class Aflamk1 : MainAPI() {
    override var mainUrl = "https://www.aflamk1.net"
    override var name = "Aflamk1"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "latest-updates" to "Latest",
        "most-popular" to "Most Popular",
        "top-rated" to "Top Rated",
        "categories/سكس-مترجم" to "Subtitled",
        "categories/سكس-امهات-مترجم" to "Moms",
        "categories/سكس-اخوات-مترجم" to "Step Sisters",
        "categories/سكس-محارم-مترجم" to "Family",
        "categories/سكس-محارم-الاب-مترجم" to "Step Dad",
        "categories/سكس-جماعى-مترجم" to "Group",
    )

    // Page URLs contain Arabic characters; OkHttp rejects non-ASCII header values, so never send
    // a page URL as the Referer — always use the site root.
    private val siteReferer get() = "$mainUrl/"

    private suspend fun getDoc(url: String): Document = app.get(url, referer = siteReferer).document

    private fun Document.videoCards(): List<SearchResponse> =
        select("div.item > a[href][title]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href"))?.takeIf { it.startsWith(mainUrl) } ?: return null
        val title = attr("title").trim().ifBlank { selectFirst("strong.title")?.text()?.trim().orEmpty() }
        if (title.isBlank()) return null
        val img = selectFirst("img")
        val poster = img?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-webp")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.startsWith("http") }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/$page/"
        val items = getDoc(url).videoCards()
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim().replace(Regex("\\s+"), "-")
        val url = if (page <= 1) "$mainUrl/search/$q/" else "$mainUrl/search/$q/?from_videos=$page"
        val document = getDoc(url)
        val items = document.videoCards()
        val hasNext = document.select("[data-parameters*=from_videos]")
            .any { it.attr("data-parameters").endsWith(":${page + 1}") }
        return newSearchResponseList(items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url)
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.title()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val tags = document.select("a[href*=/categories/], a[href*=/tags/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }
            .distinct()
        val recommendations = document.videoCards().filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
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
        val html = app.get(data, referer = siteReferer).text
        val seen = mutableSetOf<String>()

        // KVS flashvars, e.g. video_url: 'https://www.aflamk1.net/get_file/2/<hash>/1000/1201/1201.mp4/?v-acctoken=...'
        Regex("""(video_url|video_alt_url\d*)\s*:\s*'([^']+)'""").findAll(html).forEach { m ->
            val key = m.groupValues[1]
            val link = m.groupValues[2]
            if (link.startsWith("function/")) return@forEach // obfuscated (license_code) variant, not used here
            val fixed = fixUrl(link)
            if (!seen.add(fixed)) return@forEach
            val label = Regex("""${key}_text\s*:\s*'([^']*)'""").find(html)?.groupValues?.get(1).orEmpty()
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = fixed,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = siteReferer
                    this.quality = Regex("""(\d{3,4})p""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""_(\d{3,4})p\.mp4""").find(fixed)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Qualities.Unknown.value
                }
            )
        }
        return seen.isNotEmpty()
    }
}
