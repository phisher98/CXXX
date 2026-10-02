package com.Happy2hub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Happy2hub : MainAPI() {
    override var mainUrl              = "https://happy2hub.eu"
    override var name                 = "Happy2hub"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "ullu-a/"                              to "Ullu",
        "tag/primeplay-watch-online"           to "Primeplay",
        "tag/altt-watch-online"                to "Altt",
        "tag/bigshots-ott-watch-online"        to "Bigshots",
        "tag/naari-magazine-watch-online"      to "Naari",
        "tag/desiflix-originals-watch-online"  to "Desiflix",
        "tag/idiot-boxx-watch-online"          to "Idiot Boxx",
        "tag/hotshots-watch-online"            to "Hotshots",
        "tag/mx-player-watch-online"           to "MX Player",
        "tag/namastey-flix-originals"          to "Namastey Flix",
        "tag/18"                               to "All Videos",
        "tag/brazzersexxtra"                   to "Brazzers",
        "tag/mojflix-watch-online"             to "Mojflix",
        "tag/mangoflix-watch-online"           to "Mangoflix",
        "tag/hothit-watch-online"              to "Hothit",
        "tag/porn"                             to "All Porn",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/page/$page", timeout = 20L).document
        val home = document.select("div.content-wrap > div > div > div").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("h4 a, h3 a, h2 a, a.entry-title")?.text()?.takeIf { it.isNotBlank() } ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = this.selectFirst("a img, img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("a img, img")?.attr("data-src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encoded = query.trim().replace(" ", "+")
        for (i in 1..10) {
            val document = app.get("${mainUrl}/page/$i?s=$encoded").document
            val results = document.select("div.content-wrap > div > div > div").mapNotNull { it.toSearchResult() }
            if (results.isEmpty()) break
            val newItems = results.filterNot { item -> searchResponse.any { it.url == item.url } }
            if (newItems.isEmpty()) break
            searchResponse.addAll(newItems)
        }
        return searchResponse
    }

    private fun isValidLink(href: String?): Boolean {
        if (href.isNullOrBlank()) return false
        val h = href.lowercase().trim()
        if (!h.startsWith("http")) return false
        val invalid = listOf(
            "/category/", "/tag/", "/author/", "/page/", "wp-login", "wp-admin",
            "facebook.com", "twitter.com", "instagram.com", "telegram.me", "t.me",
            "whatsapp.com", "#", "javascript:"
        )
        return invalid.none { h.contains(it) }
    }

    private fun isValidCandidate(href: String?, currentUrl: String): Boolean {
        if (!isValidLink(href)) return false
        val h = href!!.trim()
        return !h.equals(currentUrl, ignoreCase = true)
    }

    private fun parseEpisodesIntoMap(doc: Document, episodeMap: MutableMap<Int, MutableList<String>>) {
        // Approach 1: Target leaf headings (h1..h6) containing episode keywords
        val headingTags = doc.select("h1, h2, h3, h4, h5, h6").filter { el ->
            val txt = el.text().trim()
            txt.contains("Episode", ignoreCase = true) ||
            txt.contains("Ep.", ignoreCase = true) ||
            txt.matches(Regex("""(?i).*\b(?:EP|Part)\s*\d+.*"""))
        }

        if (headingTags.isNotEmpty()) {
            headingTags.forEachIndexed { index, heading ->
                val epMatch = Regex("""(?i)(?:Episode|Ep\.?|Part|EP)\s*(\d+)""").find(heading.text())
                val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
                val list = episodeMap.getOrPut(epNum) { mutableListOf() }

                heading.select("a[href]").forEach { a ->
                    val h = a.attr("href")
                    if (isValidLink(h)) list.add(h)
                }

                var sibling = heading.nextElementSibling()
                while (sibling != null) {
                    val sText = sibling.text().trim()
                    val isNextHeading = sibling.tagName().matches(Regex("h[1-6]")) &&
                        (sText.contains("Episode", ignoreCase = true) || sText.matches(Regex("""(?i).*\b(?:EP|Part)\s*\d+.*""")))
                    if (isNextHeading) break

                    sibling.select("a[href]").forEach { a ->
                        val h = a.attr("href")
                        if (isValidLink(h)) list.add(h)
                    }
                    sibling = sibling.nextElementSibling()
                }
            }
        }

        // Approach 2: If no headings matched, look for <p> or <div> blocks labeled with Episode
        if (episodeMap.isEmpty()) {
            val pTags = doc.select("div.entry-content p, div.entry-content div").filter { el ->
                val own = el.ownText().trim()
                val txt = el.text().trim()
                val candidate = if (own.isNotBlank()) own else txt
                candidate.matches(Regex("""(?i)^(?:\s*Watch\s+Online\s+|\s*Download\s+)?(?:Episode|Ep\.?|Part|EP)\s*\d+.*"""))
            }

            pTags.forEachIndexed { index, pTag ->
                val epMatch = Regex("""(?i)(?:Episode|Ep\.?|Part|EP)\s*(\d+)""").find(pTag.text())
                val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
                val list = episodeMap.getOrPut(epNum) { mutableListOf() }

                pTag.select("a[href]").forEach { a ->
                    val h = a.attr("href")
                    if (isValidLink(h)) list.add(h)
                }

                var sibling = pTag.nextElementSibling()
                while (sibling != null) {
                    val sText = sibling.text().trim()
                    if (sText.matches(Regex("""(?i)^(?:\s*Watch\s+Online\s+|\s*Download\s+)?(?:Episode|Ep\.?|Part|EP)\s*\d+.*"""))) break

                    sibling.select("a[href]").forEach { a ->
                        val h = a.attr("href")
                        if (isValidLink(h)) list.add(h)
                    }
                    sibling = sibling.nextElementSibling()
                }
            }
        }

        // Approach 3: Look for individually labeled <a> tags (e.g. "Episode 1 [720p]")
        if (episodeMap.isEmpty()) {
            doc.select("div.entry-content a[href]").forEach { a ->
                val txt = a.text().trim()
                val epMatch = Regex("""(?i)(?:Episode|Ep\.?|Part|EP)\s*(\d+)""").find(txt)
                if (epMatch != null) {
                    val epNum = epMatch.groupValues[1].toInt()
                    val h = a.attr("href")
                    if (isValidLink(h)) {
                        episodeMap.getOrPut(epNum) { mutableListOf() }.add(h)
                    }
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 20L).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().substringBefore(" - Happy2Hub").trim()
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val episodeMap = linkedMapOf<Int, MutableList<String>>()

        // Step 1: Parse from main post document
        parseEpisodesIntoMap(document, episodeMap)

        // Step 2: Also check candidate intermediate / download pages
        val candidateLinks = document.select(
            "div.entry-content a[href*='download'], " +
            "div.entry-content a[href*='links'], " +
            "div.entry-content a[href*='drive'], " +
            "div.entry-content a:contains(Download), " +
            "div.entry-content a:contains(Watch Online), " +
            "div.entry-content a:contains(Click Here), " +
            "div.entry-content a.btn, div.entry-content a.button, " +
            "div.entry-content p a"
        ).map { it.attr("href") }
            .filter { isValidCandidate(it, url) }
            .distinct()

        for (candidate in candidateLinks.take(4)) {
            val subDoc = runCatching { app.get(candidate, timeout = 15L).document }.getOrNull() ?: continue
            parseEpisodesIntoMap(subDoc, episodeMap)
        }

        // Convert map into Episode objects
        val episodes = mutableListOf<Episode>()
        episodeMap.keys.sorted().forEach { epNum ->
            val links = episodeMap[epNum]?.filter { isValidLink(it) }?.distinct() ?: emptyList()
            if (links.isNotEmpty()) {
                episodes.add(newEpisode(links.joinToString(",")) {
                    this.name = "Episode $epNum"
                    this.posterUrl = poster
                    this.episode = epNum
                })
            }
        }

        // Fallback for single movies (no episode structure found)
        if (episodes.isEmpty()) {
            val allLinks = mutableListOf<String>()
            document.select("div.entry-content a[href]").forEach { a ->
                val h = a.attr("href")
                if (isValidLink(h)) allLinks.add(h)
            }
            if (allLinks.isNotEmpty()) {
                episodes.add(newEpisode(allLinks.distinct().joinToString(",")) {
                    this.name = "Full Movie"
                    this.posterUrl = poster
                    this.episode = 1
                })
            }
        }

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linksList = data.split(",")
            .map { it.trim() }
            .filter { it.startsWith("http") }
            .distinct()

        // Resolve all mirror links concurrently using amap
        linksList.amap { link ->
            resolveAndExtract(link, subtitleCallback, callback)
        }
        return true
    }

    private suspend fun resolveAndExtract(
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. PixelDrain direct stream resolution
        if (link.contains("pixeldrain.com")) {
            val id = link.substringAfterLast("/").substringBefore("?")
            if (id.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        "PixelDrain",
                        "PixelDrain",
                        "https://pixeldrain.com/api/file/$id?download",
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = ""
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return
        }

        // 2. HubCloud / VCloud / HubDrive resolver
        if (link.contains("hubcloud") || link.contains("vcloud") || link.contains("hubdrive")) {
            resolveHubCloud(link, subtitleCallback, callback)
            return
        }

        // 3. Try standard loadExtractor
        val handled = runCatching {
            loadExtractor(link, subtitleCallback, callback)
        }.getOrDefault(false)

        // 4. If intermediate redirect / download page, follow and extract from it
        if (!handled && (link.contains("happy2hub") || link.contains("download") || link.contains("links") || link.contains("go."))) {
            runCatching {
                val res = app.get(link, timeout = 15L, allowRedirects = true)
                val finalUrl = res.url
                if (finalUrl != link && (finalUrl.contains("pixeldrain") || finalUrl.contains("hubcloud") || finalUrl.contains("vcloud"))) {
                    resolveAndExtract(finalUrl, subtitleCallback, callback)
                    return
                }

                val doc = res.document

                // Extract iframes
                doc.select("iframe[src]").forEach { iframe ->
                    val src = fixUrl(iframe.attr("src"))
                    if (src.isNotBlank()) {
                        runCatching { loadExtractor(src, link, subtitleCallback, callback) }
                    }
                }

                // Extract direct video source tags
                doc.select("video source[src], source[src]").forEach { source ->
                    val src = fixUrl(source.attr("src"))
                    if (src.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                "Happy2hub",
                                "Happy2hub Direct",
                                src,
                                if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = link
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }

                // Extract and recurse into outbound stream host buttons
                doc.select("a[href]").forEach { a ->
                    val h = a.attr("href")
                    if (h.startsWith("http") && !h.contains("happy2hub") && !h.contains("/tag/")) {
                        if (h.contains("pixeldrain") || h.contains("hubcloud") || h.contains("vcloud") ||
                            h.contains("voe") || h.contains("streamtape") || h.contains("streamwish") ||
                            h.contains("dood") || h.contains("filelions") || h.contains("vidhide") ||
                            h.contains("fastdl") || h.contains("gofile")) {
                            resolveAndExtract(h, subtitleCallback, callback)
                        }
                    }
                }
            }
        }
    }

    private suspend fun resolveHubCloud(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            var href = url
            if (href.contains("api/index.php")) {
                href = app.get(url).document.selectFirst("div.main h4 a")?.attr("href") ?: url
            }
            val doc = app.get(href).document
            val scriptTag = doc.selectFirst("script:containsData(url)")?.data()
                ?: doc.selectFirst("script:containsData(location)")?.data().orEmpty()
            val urlValue = Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.getOrNull(1).orEmpty()
            val targetUrl = if (urlValue.isNotEmpty()) urlValue else href

            val document = if (targetUrl != href) app.get(targetUrl).document else doc
            val size = document.selectFirst("i#size")?.text().orEmpty()
            val labelExtras = if (size.isNotEmpty()) "[$size]" else ""

            document.select("div.card-body h2 a.btn, a.btn, a[href*='download'], a[href*='token']").forEach { btn ->
                val btnLink = btn.attr("href")
                val text = btn.text()

                when {
                    text.contains("pixel", ignoreCase = true) || btnLink.contains("pixeldrain.com") -> {
                        val id = btnLink.substringAfterLast("/").substringBefore("?")
                        val finalURL = "https://pixeldrain.com/api/file/$id?download"
                        callback.invoke(
                            newExtractorLink(
                                "PixelDrain",
                                "PixelDrain $labelExtras",
                                finalURL,
                                ExtractorLinkType.VIDEO
                            ) { this.quality = Qualities.Unknown.value }
                        )
                    }

                    text.contains("BuzzServer", ignoreCase = true) || text.contains("FastDL", ignoreCase = true) -> {
                        runCatching {
                            val dlink = app.get("$btnLink/download", referer = btnLink, allowRedirects = false).headers["hx-redirect"]
                                ?: app.get(btnLink, referer = href, allowRedirects = false).headers["location"]
                            val base = btnLink.substringBefore("/download").substringBefore("://") + "://" + btnLink.substringAfter("://").substringBefore("/")
                            val fullStream = if (!dlink.isNullOrBlank()) {
                                if (dlink.startsWith("http")) dlink else base + dlink
                            } else btnLink

                            callback.invoke(
                                newExtractorLink(
                                    "BuzzServer",
                                    "BuzzServer $labelExtras",
                                    fullStream,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = btnLink
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }

                    text.contains("FSL", ignoreCase = true) || text.contains("FastServer", ignoreCase = true) || text.contains("Direct", ignoreCase = true) -> {
                        if (btnLink.startsWith("http")) {
                            callback.invoke(
                                newExtractorLink(
                                    text.trim().ifEmpty { "FastServer" },
                                    "${text.trim().ifEmpty { "FastServer" }} $labelExtras",
                                    btnLink,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = href
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }

                    else -> {
                        runCatching { loadExtractor(btnLink, href, subtitleCallback, callback) }
                    }
                }
            }
        }
    }
}