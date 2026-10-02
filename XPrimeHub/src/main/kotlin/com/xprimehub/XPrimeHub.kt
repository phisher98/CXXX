@file:Suppress("NAME_SHADOWING")

package com.xprimehub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element
import java.net.URI
import java.text.Normalizer

class XPrimeHub : MainAPI() {
    override var mainUrl: String = runBlocking {
        XPrimeHubProvider.getDomains()?.xprimehub?.takeIf { it.isNotBlank() } ?: "https://xprimehub.skin"
    }
    override var name                 = "XPrimeHub"
    override val hasMainPage          = true
    override var lang                 = "hi"
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "Home",
        "dual-audio" to "Dual Audio",
        "kooku" to "Kooku",
        "tagalog" to "Tagalog",
        "hotx-originals" to "HotX Originals",
        "ullu-originals" to "Ullu Originals",
        "moodx" to "MoodX",
        "neonx-originals" to "NeonX",
        "niksindian" to "NiksIndian",
        "onlyfans" to "OnlyFans",
        "sexmex" to "SexMex",
        "triflicks" to "TriFlicks",
        "xprime" to "XPrime",
        "english" to "English",
        "by-genres/brazzers" to "Brazzers",
        "french" to "French",
        "japanese" to "Japanese",
        "korean" to "Korean",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trim('/')
        val url = if (path.isEmpty()) {
            if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        } else {
            if (page <= 1) "$mainUrl/$path/" else "$mainUrl/$path/page/$page/"
        }

        val document = app.get(url).document
        val home = document.select("div.movies-grid a, .elementor-loop-container .e-loop-item, div.movies-grid > div")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("p.poster-title, h3, h2, a.title")?.text()
            ?.substringAfter("[18+]")
            ?.substringBefore("UNRATED")
            ?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null

        val href = fixUrlNull(this.attr("href").ifEmpty { this.selectFirst("a")?.attr("href") }) ?: return null

        val posterUrl = this.selectFirst("img")?.attr("data-lazy-src")?.takeIf { it.startsWith("http") }
            ?: this.selectFirst("img")?.attr("data-src")?.takeIf { it.startsWith("http") }
            ?: this.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }

        val quality = getSearchQuality(this.selectFirst("span.poster-quality, span.quality")?.text())

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = query.trim().replace(" ", "+")

        // 1. Try search.php API first
        val apiResults = runCatching {
            val response = app.get("$mainUrl/search.php?q=$encoded&page=$page").parsedSafe<Search>()
            response?.hits?.mapNotNull { hit ->
                val doc = hit.document
                val href = fixUrlNull(doc.permalink) ?: return@mapNotNull null
                val title = doc.post_title.substringAfter("[18+]").substringBefore("UNRATED").trim()
                newMovieSearchResponse(title, href, TvType.NSFW) {
                    this.posterUrl = doc.post_thumbnail
                }
            }
        }.getOrNull()

        if (!apiResults.isNullOrEmpty()) {
            return apiResults.toNewSearchResponseList()
        }

        // 2. Fallback to standard WordPress search
        val searchUrl = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val document = app.get(searchUrl).document
        val htmlResults = document.select("div.movies-grid a, .elementor-loop-container .e-loop-item, div.result-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return htmlResults.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = (document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: document.selectFirst("head title")?.text() ?: "No Title")
            .substringAfter("[18+]")
            .substringBefore("UNRATED")
            .substringBefore("Series")
            .trim()

        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val bgposter = fixUrlNull(
            document.selectFirst("div.entry-content img, .elementor-widget-theme-post-content img")?.attr("data-lazy-src")
                ?: document.selectFirst("div.entry-content img, .elementor-widget-theme-post-content img")?.attr("src")
                ?: poster
        )

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("div.entry-content p, .elementor-widget-theme-post-content p")?.text()?.trim()

        val episodesList = mutableListOf<Episode>()

        // 1. Check headings (h2..h6)
        val episodeBlocks = document.select("h2, h3, h4, h5, h6").filter {
            val t = it.text()
            t.contains("Episode", ignoreCase = true) || t.contains("Ep", ignoreCase = true) ||
            t.contains("Part", ignoreCase = true) || t.contains("720p", ignoreCase = true) ||
            t.contains("1080p", ignoreCase = true) || t.contains("480p", ignoreCase = true) ||
            t.contains("Zip", ignoreCase = true) || t.contains("Pack", ignoreCase = true)
        }

        episodeBlocks.forEachIndexed { index, ep ->
            val cleanName = ep.text().replace("To", "-").trim().ifEmpty { "Episode ${index + 1}" }
            val epNum = Regex("""(?i)(?:Episode|Ep\.?|Part)\s*(\d+)""").find(cleanName)?.groupValues?.get(1)?.toIntOrNull()

            val links = mutableListOf<String>()
            var sibling = ep.nextElementSibling()
            while (sibling != null) {
                if (sibling.tagName().matches(Regex("h[1-6]"))) break
                sibling.select("a[href]").forEach { a ->
                    val h = a.attr("href")
                    if (h.startsWith("http") && !h.contains("/tag/") && !h.contains("/category/")) {
                        links.add(h)
                    }
                }
                sibling = sibling.nextElementSibling()
            }

            if (links.isNotEmpty()) {
                episodesList.add(
                    newEpisode(links.distinct().joinToString(",")) {
                        this.name = cleanName
                        this.episode = epNum ?: (index + 1)
                    }
                )
            }
        }

        // 2. Fallback: collect buttons or links if no headings matched
        if (episodesList.isEmpty()) {
            val buttonLinks = mutableListOf<String>()
            document.select("button.btn, a.btn, a.button, div.entry-content p a, .elementor-widget-theme-post-content a").forEach { btn ->
                val link = (if (btn.tagName() == "a") btn.attr("href") else btn.closest("a")?.attr("href")).orEmpty()
                if (link.startsWith("http") && !link.contains("/tag/") && !link.contains("/category/")) {
                    buttonLinks.add(link)
                }
            }

            if (buttonLinks.isNotEmpty()) {
                episodesList.add(
                    newEpisode(buttonLinks.distinct().joinToString(",")) {
                        this.name = "Full Movie / Video"
                        this.episode = 1
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodesList) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bgposter
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val excludedTexts = setOf("Filepress", "GDToT", "DropGalaxy")

        val links = data.split(",").map { it.trim() }.filter { it.startsWith("http") }.distinct()

        links.amap { link ->
            resolveXPrimeLink(link, excludedTexts, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun resolveXPrimeLink(
        link: String,
        excludedTexts: Set<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Direct host checks
        if (link.contains("pixeldrain.com")) {
            val id = link.substringAfterLast("/").substringBefore("?")
            if (id.isNotBlank()) {
                callback.invoke(
                    newExtractorLink("PixelDrain", "PixelDrain", "https://pixeldrain.com/api/file/$id?download", ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return
        }

        if (link.contains("vcloud") || link.contains("hubcloud") || link.contains("hubdrive")) {
            VCloud().getUrl(link, "$mainUrl/", subtitleCallback, callback)
            return
        }

        // Try standard extractor
        val handled = runCatching {
            loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
        }.getOrDefault(false)

        if (handled) return

        // Intermediate landing page: fetch and extract buttons/links
        runCatching {
            val doc = app.get(link, timeout = 15L, allowRedirects = true).document
            val extractedUrls = doc.select("button.btn, a.btn, a.button, h2 a, div.card-body a")
                .filterNot { el ->
                    val text = el.text()
                    excludedTexts.any { text.contains(it, ignoreCase = true) }
                }
                .mapNotNull {
                    val h = if (it.tagName() == "a") it.attr("href") else it.closest("a")?.attr("href")
                    h?.takeIf { s -> s.startsWith("http") }
                }
                .distinct()

            extractedUrls.amap { streamUrl ->
                if (streamUrl.contains("pixeldrain.com")) {
                    val id = streamUrl.substringAfterLast("/").substringBefore("?")
                    callback.invoke(
                        newExtractorLink("PixelDrain", "PixelDrain", "https://pixeldrain.com/api/file/$id?download", ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } else if (streamUrl.contains("vcloud") || streamUrl.contains("hubcloud") || streamUrl.contains("hubdrive")) {
                    VCloud().getUrl(streamUrl, link, subtitleCallback, callback)
                } else {
                    loadExtractor(streamUrl, link, subtitleCallback, callback)
                }
            }
        }
    }
}

class HubCloud : ExtractorApi() {
    override val name: String = "HubCloud"
    override val mainUrl: String = "https://hubcloud.one"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        VCloud().getUrl(url, referer, subtitleCallback, callback)
    }
}

class HubCloudClub : ExtractorApi() {
    override val name: String = "HubCloud"
    override val mainUrl: String = "https://hubcloud.club"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        VCloud().getUrl(url, referer, subtitleCallback, callback)
    }
}

class VCloud : ExtractorApi() {
    override val name: String = "V-Cloud"
    override val mainUrl: String = "https://vcloud.zip"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var href = url

        if (href.contains("api/index.php")) {
            href = runCatching {
                app.get(url).document.selectFirst("div.main h4 a")?.attr("href")
            }.getOrNull() ?: return
        }

        val doc = runCatching { app.get(href).document }.getOrNull() ?: return
        val scriptTag = doc.selectFirst("script:containsData(url)")?.data() ?: ""
        val urlValue = Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.getOrNull(1).orEmpty()
        val targetUrl = if (urlValue.isNotEmpty()) urlValue else href

        val document = if (targetUrl != href) (runCatching { app.get(targetUrl).document }.getOrNull() ?: doc) else doc
        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        val labelExtras = buildString {
            if (header.isNotEmpty()) append(header)
            if (size.isNotEmpty()) append(" [$size]")
        }

        val div = document.selectFirst("div.card-body") ?: return

        div.select("h2 a.btn, a.btn").amap {
            val link = it.attr("href")
            val text = it.text()
            val quality = getIndexQuality(header)

            when {
                text.contains("FSLv2", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "FSLv2",
                            "[FSLv2] $labelExtras",
                            link,
                        ) { this.quality = quality }
                    )
                }

                text.contains("FSL") -> {
                    callback.invoke(
                        newExtractorLink(
                            "FSL Server",
                            "[FSL Server] $labelExtras",
                            link,
                        ) { this.quality = quality }
                    )
                }

                text.contains("BuzzServer") || text.contains("FastDL", ignoreCase = true) -> {
                    val dlink = runCatching {
                        app.get("$link/download", referer = link, allowRedirects = false).headers["hx-redirect"]
                            ?: app.get(link, referer = href, allowRedirects = false).headers["location"]
                    }.getOrNull() ?: ""
                    val baseUrl = getBaseUrl(link)
                    if (dlink.isNotEmpty()) {
                        val fullStream = if (dlink.startsWith("http")) dlink else baseUrl + dlink
                        callback.invoke(
                            newExtractorLink(
                                "BuzzServer",
                                "[BuzzServer] $labelExtras",
                                fullStream,
                            ) { this.quality = quality }
                        )
                    }
                }

                text.contains("pixeldra", ignoreCase = true) || text.contains("pixel", ignoreCase = true) || text.contains("PixeLServer", ignoreCase = true) || link.contains("pixeldrain.com") -> {
                    val id = link.substringAfterLast("/").substringBefore("?")
                    val finalURL = "https://pixeldrain.com/api/file/$id?download"

                    callback(
                        newExtractorLink(
                            "PixelDrain",
                            "[PixelDrain] $labelExtras",
                            finalURL
                        ) { this.quality = quality }
                    )
                }

                text.contains("PDL Server") -> {
                    callback.invoke(
                        newExtractorLink(
                            "PDL Server",
                            "[PDL Server] $labelExtras",
                            link,
                        ) { this.quality = quality }
                    )
                }

                text.contains("S3 Server", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "S3 Server",
                            "[S3 Server] $labelExtras",
                            link,
                        ) { this.quality = quality }
                    )
                }

                text.contains("Mega Server", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "Mega Server",
                            "[Mega Server] $labelExtras",
                            link,
                        ) { this.quality = quality }
                    )
                }

                else -> {
                    loadExtractor(link, "", subtitleCallback, callback)
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return extractIndexQuality(str)
    }
    private val extractorQualityRegex = Regex("(\\d{3,4})[pP]")
    private fun extractIndexQuality(str: String?, defaultQuality: Int = Qualities.Unknown.value): Int {
        return extractorQualityRegex.find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: defaultQuality
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault("")
    }
}

fun getSearchQuality(check: String?): SearchQuality? {
    val s = check ?: return null
    val u = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
    val patterns = listOf(
        Regex("\\b(4k|ds4k|uhd|2160p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.FourK,
        Regex("\\b(hdts|hdcam|hdtc)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HdCam,
        Regex("\\b(camrip|cam[- ]?rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip,
        Regex("\\b(cam)\\b", RegexOption.IGNORE_CASE) to SearchQuality.Cam,
        Regex("\\b(web[- ]?dl|webrip|webdl)\\b", RegexOption.IGNORE_CASE) to SearchQuality.WebRip,
        Regex("\\b(bluray|bdrip|blu[- ]?ray)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
        Regex("\\b(1440p|qhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
        Regex("\\b(1080p|fullhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
        Regex("\\b(720p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.SD,
        Regex("\\b(hdrip|hdtv)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
        Regex("\\b(dvd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.DVD,
        Regex("\\b(hq)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HQ,
        Regex("\\b(rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip
    )

    for ((regex, quality) in patterns) if (regex.containsMatchIn(u)) return quality
    return null
}

data class Search(
    val hits: List<Hit>
)

data class Hit(
    val document: DocumentData
)

data class DocumentData(
    val id: String,
    val post_title: String,
    val post_thumbnail: String?,
    val permalink: String,
    val post_date: String?
)