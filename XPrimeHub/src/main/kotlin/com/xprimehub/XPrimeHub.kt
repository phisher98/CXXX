@file:Suppress("NAME_SHADOWING")

package com.xprimehub

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element
import java.net.URI
import java.text.Normalizer

class XPrimeHub : MainAPI() {
    override var mainUrl: String = runBlocking {
        XPrimeHubProvider.getDomains()?.xprimehub?.takeIf { it.isNotBlank() } ?: "https://xprimehub.pics"
    }
    override var name                 = "XPrimeHub"
    override val hasMainPage          = true
    override var lang                 = "hi"
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "Home",
        "english" to "English",
        "by-genres/brazzers" to "Brazzers",
        "hindi-dubbed" to "Hindi Dubbed",
        "sexmex" to "SexMex",
        "niksindian" to "NiksIndian",
        "korean" to "Korean",
        "russian" to "Russian",
        "tagalog" to "Tagalog",
        "onlyfans" to "OnlyFans",
        "dual-audio" to "Dual Audio",
        "kooku" to "Kooku",
        "hotx-originals" to "HotX Originals",
        "ullu-originals" to "Ullu Originals",
        "moodx" to "MoodX",
        "neonx-originals" to "NeonX",
        "triflicks" to "TriFlicks",
        "xprime" to "XPrime",
        "french" to "French",
        "japanese" to "Japanese",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trim('/')
        val url = if (path.isEmpty()) {
            if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        } else {
            if (page <= 1) "$mainUrl/$path/" else "$mainUrl/$path/page/$page/"
        }

        val document = app.get(url).document
        val home = document.select("article.post, article.generate-columns, article, div.movies-grid a, .elementor-loop-container .e-loop-item, div.movies-grid > div")
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
        val rawTitle = this.selectFirst("h2.entry-title, .entry-title, p.poster-title, h3, h2, a.title")?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null

        val title = cleanTitle(rawTitle).ifEmpty { rawTitle }

        val href = fixUrlNull(
            this.attr("href").ifEmpty {
                this.selectFirst("h2.entry-title a, a.post-image, a")?.attr("href")
            }
        ) ?: return null

        val img = this.selectFirst("img")
        val posterUrl = img?.attr("data-lazy-src")?.takeIf { it.startsWith("http") }
            ?: img?.attr("data-src")?.takeIf { it.startsWith("http") }
            ?: img?.attr("srcset")?.split(",")?.firstOrNull()?.trim()?.substringBefore(" ")?.takeIf { it.startsWith("http") }
            ?: img?.attr("src")?.takeIf { it.startsWith("http") }

        val quality = getSearchQuality(this.selectFirst("span.poster-quality, span.quality")?.text())
            ?: getSearchQuality(rawTitle)

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
                val doc = hit.document ?: return@mapNotNull null
                val permalink = doc.permalink ?: return@mapNotNull null
                val href = fixUrlNull(permalink) ?: return@mapNotNull null
                val rawTitle = doc.post_title ?: return@mapNotNull null
                val title = cleanTitle(rawTitle).ifEmpty { rawTitle }
                val quality = getSearchQuality(rawTitle)
                newMovieSearchResponse(title, href, TvType.NSFW) {
                    this.posterUrl = doc.post_thumbnail
                    this.quality = quality
                }
            }
        }.getOrNull()

        if (!apiResults.isNullOrEmpty()) {
            return apiResults.toNewSearchResponseList()
        }

        // 2. Fallback to standard WordPress search
        val searchUrl = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val document = app.get(searchUrl).document
        val htmlResults = document.select("article.post, article.generate-columns, article, div.movies-grid a, .elementor-loop-container .e-loop-item, div.result-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return htmlResults.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val rawTitle = (document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: document.selectFirst("h1.entry-title, h1, head title")?.text() ?: "No Title")
        val title = cleanTitle(rawTitle).ifEmpty { rawTitle }

        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val bgposter = fixUrlNull(
            document.selectFirst("div.entry-content img, .elementor-widget-theme-post-content img, article img")?.attr("data-lazy-src")
                ?: document.selectFirst("div.entry-content img, .elementor-widget-theme-post-content img, article img")?.attr("src")
                ?: poster
        )

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("div.entry-content p, .elementor-widget-theme-post-content p")?.text()?.trim()

        val episodesList = mutableListOf<Episode>()

        // 1. Check headings (h2..h6) for episodes/parts/qualities
        val episodeBlocks = document.select("h2, h3, h4, h5, h6").filter {
            val t = it.text()
            t.contains("Episode", ignoreCase = true) || t.contains("Ep", ignoreCase = true) ||
            t.contains("Part", ignoreCase = true) || t.contains("720p", ignoreCase = true) ||
            t.contains("1080p", ignoreCase = true) || t.contains("480p", ignoreCase = true) ||
            t.contains("Zip", ignoreCase = true) || t.contains("Pack", ignoreCase = true)
        }

        for ((index, ep) in episodeBlocks.withIndex()) {
            val cleanName = cleanHeadingName(ep.text().replace("To", "-").trim().ifEmpty { "Episode ${index + 1}" })
            val epNum = Regex("""(?i)(?:Episode|Ep\.?|Part)\s*(\d+)""").find(cleanName)?.groupValues?.get(1)?.toIntOrNull()

            val links = mutableListOf<String>()
            var sibling = ep.nextElementSibling()
            while (sibling != null) {
                if (sibling.tagName().matches(Regex("h[1-6]"))) break
                sibling.select("a[href]").forEach { a ->
                    val h = a.attr("href")
                    if (h.startsWith("http") && !isExcludedUrl(h)) {
                        links.add(h)
                    }
                }
                sibling = sibling.nextElementSibling()
            }

            // Check if this heading has a "Single Episode" link pointing to a multi-episode landing page
            val singleEpLink = links.find { it.contains("genxfm") || it.contains("nexdrive") }
            if (cleanName.contains("Single Episode", ignoreCase = true) && singleEpLink != null) {
                val nestedEpisodes = parseNexdriveEpisodes(singleEpLink)
                if (nestedEpisodes.isNotEmpty()) {
                    episodesList.addAll(nestedEpisodes)
                    continue
                }
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
            document.select("a.xp-download-btn, button.btn, a.btn, a.button, div.entry-content p a, .elementor-widget-theme-post-content a").forEach { btn ->
                val link = (if (btn.tagName() == "a") btn.attr("href") else btn.closest("a")?.attr("href")).orEmpty()
                val text = btn.text().trim()
                if (link.startsWith("http") && !isExcludedUrl(link) && text != "XPrimehub" && text.isNotBlank()) {
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

    private suspend fun parseNexdriveEpisodes(url: String): List<Episode> {
        return runCatching {
            val doc = app.get(url, timeout = 10L).document
            val epHeadings = doc.select("h3, h4, h5, h6").filter {
                val t = it.text().lowercase()
                t.contains("episode") || t.contains("ep")
            }
            val list = mutableListOf<Episode>()
            epHeadings.forEach { h ->
                val text = h.text().trim()
                val num = Regex("""\d+""").find(text)?.groupValues?.getOrNull(0)?.toIntOrNull() ?: return@forEach
                val links = mutableListOf<String>()
                var sib = h.nextElementSibling()
                while (sib != null) {
                    if (sib.tagName().matches(Regex("h[1-6]"))) break
                    sib.select("a[href]").forEach { a ->
                        val href = a.attr("href")
                        val aText = a.text()
                        if (href.startsWith("http") && !isExcludedUrl(href) && !isExcludedText(aText)) {
                            links.add(href)
                        }
                    }
                    sib = sib.nextElementSibling()
                }
                if (links.isNotEmpty()) {
                    list.add(
                        newEpisode(links.distinct().joinToString(",")) {
                            this.name = "Episode $num"
                            this.episode = num
                        }
                    )
                }
            }
            list
        }.getOrDefault(emptyList())
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = data.split(",").map { it.trim() }.filter { it.startsWith("http") }.distinct()

        links.amap { link ->
            resolveXPrimeLink(link, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun resolveXPrimeLink(
        link: String,
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

        if (link.contains("fastdl")) {
            FastDL().getUrl(link, "$mainUrl/", subtitleCallback, callback)
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

        // Intermediate landing page (nexdrive, etc.): fetch and extract buttons/links
        runCatching {
            val doc = app.get(link, timeout = 15L, allowRedirects = true).document
            val extractedUrls = doc.select("a.xp-download-btn, button.btn, a.btn, a.button, h2 a, h4 a, h5 a, div.card-body a, a[href]")
                .filterNot { el ->
                    val text = el.text()
                    val href = el.attr("href")
                    isExcludedText(text) || isExcludedUrl(href)
                }
                .mapNotNull {
                    val h = if (it.tagName() == "a") it.attr("href") else it.closest("a")?.attr("href")
                    h?.takeIf { s -> s.startsWith("http") && !isExcludedUrl(s) }
                }
                .distinct()

            extractedUrls.amap { streamUrl ->
                when {
                    streamUrl.contains("pixeldrain.com") -> {
                        val id = streamUrl.substringAfterLast("/").substringBefore("?")
                        callback.invoke(
                            newExtractorLink("PixelDrain", "PixelDrain", "https://pixeldrain.com/api/file/$id?download", ExtractorLinkType.VIDEO) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                    streamUrl.contains("fastdl") -> {
                        FastDL().getUrl(streamUrl, link, subtitleCallback, callback)
                    }
                    streamUrl.contains("vcloud") || streamUrl.contains("hubcloud") || streamUrl.contains("hubdrive") -> {
                        VCloud().getUrl(streamUrl, link, subtitleCallback, callback)
                    }
                    else -> {
                        loadExtractor(streamUrl, link, subtitleCallback, callback)
                    }
                }
            }
        }
    }

    private fun cleanTitle(title: String): String {
        return title
            .substringAfter("[18+]")
            .substringBefore("UNRATED")
            .substringBefore("| Free Download")
            .substringBefore("Free Download")
            .substringBefore("~ Xprimehub")
            .substringBefore("~Xprimehub")
            .substringBefore("- XprimeHub")
            .substringBefore("- XPrimehub")
            .trim()
    }

    private fun cleanHeadingName(name: String): String {
        return name
            .replace(Regex("""^\s*⚡\s*"""), "")
            .replace(Regex("""\s*⚡\s*$"""), "")
            .trim()
    }

    private fun isExcludedUrl(url: String): Boolean {
        val lower = url.lowercase()
        val excluded = listOf(
            "/tag/", "/category/", "/c/", "gdtot", "filepress",
            "dropgalaxy", "vglist.cv", "telegram", "t.me",
            "bit.ly", "snvhost.com"
        )
        return excluded.any { lower.contains(it) }
    }

    private fun isExcludedText(text: String): Boolean {
        val lower = text.lowercase()
        val excluded = listOf("filepress", "gdtot", "dropgalaxy", "vglist", "telegram", "tutorial")
        return excluded.any { lower.contains(it) }
    }
}

class FastDL : ExtractorApi() {
    override val name: String = "FastDL"
    override val mainUrl: String = "https://fastdl.beer"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Direct link parameter: dl.php?link=https://...
        if (url.contains("link=")) {
            val direct = url.substringAfter("link=").substringBefore("&")
            if (direct.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        direct,
                        ExtractorLinkType.VIDEO
                    ) { this.quality = Qualities.Unknown.value }
                )
                return
            }
        }

        val ref = referer ?: "$mainUrl/"
        val res = runCatching { app.get(url, referer = ref).text }.getOrNull() ?: return
        val reurl = Regex("""var\s+reurl\s*=\s*["']([^"']+)["']""").find(res)?.groupValues?.getOrNull(1)
        if (!reurl.isNullOrBlank()) {
            val direct = if (reurl.contains("link=")) {
                reurl.substringAfter("link=").substringBefore("&")
            } else reurl

            if (direct.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        direct,
                        ExtractorLinkType.VIDEO
                    ) { this.quality = Qualities.Unknown.value }
                )
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
    override val mainUrl: String = "https://vcloud.beer"
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
            }.getOrNull() ?: href
        }

        val doc = runCatching { app.get(href, referer = referer).document }.getOrNull() ?: return
        val scriptTag = doc.selectFirst("script:containsData(url)")?.data()
            ?: doc.selectFirst("script:containsData(atob)")?.data()
            ?: doc.selectFirst("script:containsData(location)")?.data().orEmpty()

        val targetUrl = extractTargetUrl(scriptTag) ?: href

        val document = if (targetUrl != href) {
            runCatching { app.get(targetUrl, referer = href).document }.getOrNull() ?: doc
        } else doc

        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        val labelExtras = buildString {
            if (header.isNotEmpty()) append(header)
            if (size.isNotEmpty()) append(" [$size]")
        }

        val quality = getIndexQuality(header)

        val div = document.selectFirst("div.card-body") ?: return

        div.select("h2 a.btn, a.btn, a[href]").amap { el ->
            val link = el.attr("href")
            val text = el.text()

            if (!link.startsWith("http")) return@amap
            if (excludedHosts.any { link.contains(it, ignoreCase = true) || text.contains(it, ignoreCase = true) }) return@amap

            when {
                text.contains("FSLv2", ignoreCase = true) || text.contains("FSL", ignoreCase = true) || text.contains("FastServer", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "FSL Server",
                            "[FSL Server] $labelExtras",
                            link,
                            ExtractorLinkType.VIDEO
                        ) { this.quality = quality }
                    )
                }

                text.contains("BuzzServer", ignoreCase = true) || text.contains("FastDL", ignoreCase = true) -> {
                    FastDL().getUrl(link, targetUrl, subtitleCallback, callback)
                }

                text.contains("10Gbps", ignoreCase = true) || link.contains("gpdl") || link.contains("hubcloud") -> {
                    runCatching {
                        val resp = app.get(link, referer = targetUrl, allowRedirects = true)
                        val finalUrl = resp.url
                        val direct = if (finalUrl.contains("link=")) {
                            finalUrl.substringAfter("link=").substringBefore("&")
                        } else {
                            resp.document.selectFirst("a[href*='googleusercontent'], a[href*='dl.php?link=']")?.attr("href")?.let {
                                if (it.contains("link=")) it.substringAfter("link=").substringBefore("&") else it
                            }
                        }
                        if (!direct.isNullOrBlank() && direct.startsWith("http")) {
                            callback.invoke(
                                newExtractorLink(
                                    "HubCloud 10Gbps",
                                    "[10Gbps] $labelExtras",
                                    direct,
                                    ExtractorLinkType.VIDEO
                                ) { this.quality = quality }
                            )
                        }
                    }
                }

                text.contains("pixeldra", ignoreCase = true) || text.contains("pixel", ignoreCase = true) || link.contains("pixeldrain.com") -> {
                    val id = link.substringAfterLast("/").substringBefore("?")
                    val finalURL = "https://pixeldrain.com/api/file/$id?download"
                    callback.invoke(
                        newExtractorLink(
                            "PixelDrain",
                            "[PixelDrain] $labelExtras",
                            finalURL,
                            ExtractorLinkType.VIDEO
                        ) { this.quality = quality }
                    )
                }

                text.contains("PDL Server", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "PDL Server",
                            "[PDL Server] $labelExtras",
                            link,
                            ExtractorLinkType.VIDEO
                        ) { this.quality = quality }
                    )
                }

                text.contains("S3 Server", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "S3 Server",
                            "[S3 Server] $labelExtras",
                            link,
                            ExtractorLinkType.VIDEO
                        ) { this.quality = quality }
                    )
                }

                text.contains("Mega Server", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "Mega Server",
                            "[Mega Server] $labelExtras",
                            link,
                            ExtractorLinkType.VIDEO
                        ) { this.quality = quality }
                    )
                }

                else -> {
                    loadExtractor(link, targetUrl, subtitleCallback, callback)
                }
            }
        }
    }

    private fun extractTargetUrl(script: String): String? {
        val doubleAtob = Regex("""url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""").find(script)?.groupValues?.getOrNull(1)
        if (!doubleAtob.isNullOrBlank()) {
            val s1 = runCatching { String(android.util.Base64.decode(doubleAtob, android.util.Base64.DEFAULT)) }.getOrNull()
            val s2 = runCatching { s1?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) } }.getOrNull()
            if (s2?.startsWith("http") == true) return s2
        }

        val singleAtob = Regex("""url\s*=\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)""").find(script)?.groupValues?.getOrNull(1)
        if (!singleAtob.isNullOrBlank()) {
            val s1 = runCatching { String(android.util.Base64.decode(singleAtob, android.util.Base64.DEFAULT)) }.getOrNull()
            if (s1?.startsWith("http") == true) return s1
        }

        val raw = Regex("""url\s*=\s*['"]([^'"]+)['"]""").find(script)?.groupValues?.getOrNull(1)
        if (!raw.isNullOrBlank() && raw.startsWith("http")) return raw

        return null
    }

    private fun getIndexQuality(str: String?): Int {
        return extractIndexQuality(str)
    }

    private val extractorQualityRegex = Regex("(\\d{3,4})[pP]")
    private fun extractIndexQuality(str: String?, defaultQuality: Int = Qualities.Unknown.value): Int {
        return extractorQualityRegex.find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: defaultQuality
    }

    companion object {
        val excludedHosts = setOf("bit.ly", "t.me", "google.com", "snvhost", "idm", "ida")
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class Search(
    @JsonProperty("hits")
    val hits: List<Hit>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Hit(
    @JsonProperty("document")
    val document: DocumentData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DocumentData(
    @JsonProperty("id")
    val id: String? = null,
    @JsonProperty("post_title")
    val post_title: String? = null,
    @JsonProperty("post_thumbnail")
    val post_thumbnail: String? = null,
    @JsonProperty("permalink")
    val permalink: String? = null,
    @JsonProperty("post_date")
    val post_date: String? = null
)