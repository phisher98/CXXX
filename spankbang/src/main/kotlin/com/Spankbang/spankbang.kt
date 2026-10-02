package com.coxju

import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller

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

    private val cloudflareKiller = CloudflareKiller()

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    private val baseCookies = mapOf(
        "age_pass" to "1",
        "cookie_consent_required" to "0",
        "show_cookie_consent_modal" to "0",
        "av" to "simple:True:True",
        "backend_version" to "main",
        "media_layout" to "four-col",
        "coc" to "IN",
        "country" to "US"
    )

    init {
        runCatching {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            manager.setCookie(mainUrl, "age_pass=1; path=/; domain=.spankbang.com")
            manager.setCookie(mainUrl, "cookie_consent_required=0; path=/; domain=.spankbang.com")
            manager.setCookie(mainUrl, "show_cookie_consent_modal=0; path=/; domain=.spankbang.com")
            manager.setCookie(mainUrl, "country=US; path=/; domain=.spankbang.com")
            manager.setCookie(mainUrl, "av=simple:True:True; path=/; domain=.spankbang.com")
            manager.setCookie(mainUrl, "media_layout=four-col; path=/; domain=.spankbang.com")
        }
    }

    private fun getCookies(url: String): Map<String, String> {
        val cookies = baseCookies.toMutableMap()
        runCatching {
            val manager = CookieManager.getInstance()
            val cookieStr = manager.getCookie(url) ?: manager.getCookie(mainUrl)
            if (!cookieStr.isNullOrBlank()) {
                cookieStr.split(";").forEach {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) {
                        cookies[parts[0].trim()] = parts[1].trim()
                    }
                }
            }
        }
        return cookies
    }

    private suspend fun getDocument(url: String): Document {
        return try {
            app.get(
                url,
                headers = defaultHeaders,
                referer = "$mainUrl/",
                cookies = getCookies(url),
                interceptor = cloudflareKiller
            ).document
        } catch (e: Exception) {
            app.get(
                url,
                headers = defaultHeaders,
                referer = "$mainUrl/",
                cookies = getCookies(url)
            ).document
        }
    }

    override val mainPage = mainPageOf(
        "trending_videos/"              to "Trending",
        "new_videos/"                   to "New Videos",
        "most_popular/"                 to "Most Popular",
        "top_videos/"                   to "Top Rated",
        "j2/channel/familyxxx/"         to "Family XXX",
        "ce/channel/bratty+milf/"       to "Bratty MILF",
        "cf/channel/bratty+sis/"        to "Bratty Sis",
        "k5/channel/japan+hdv/"         to "Japan HDV",
        "j3/channel/hot+wife+xxx/"      to "Hot Wife XXX",
        "d6/channel/my+family+pies/"    to "My Family Pies",
        "ho/channel/brazzers/"          to "Brazzers",
        "6l/channel/mylf/"              to "MYLF",
        "9n/channel/evil+angel/"        to "Evil Angel",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trim('/')
        val url = if (path.isEmpty()) {
            if (page <= 1) "$mainUrl/trending_videos/" else "$mainUrl/trending_videos/$page/"
        } else {
            if (page <= 1) "$mainUrl/$path/" else "$mainUrl/$path/$page/"
        }

        val document = getDocument(url)
        val home = parseVideos(document)

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun parseVideos(document: Document): List<SearchResponse> {
        val containers = document.select(
            "div.video-item, div.video_item, div[data-id], div.video-rotate, .video-list > div, .video-list-with-ads > div, article"
        )
        val containerResults = containers.mapNotNull { it.toSearchResult() }
        val results = if (containerResults.isNotEmpty()) {
            containerResults
        } else {
            document.select("a.thumb, a[href*='/video/'], a[href*='/play/']").mapNotNull { it.toSearchResult() }
        }
        return results.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = if (this.tagName() == "a" && (this.attr("href").contains("/video/") || this.attr("href").contains("/play/"))) {
            this
        } else {
            this.selectFirst("a.thumb, a[href*='/video/'], a[href*='/play/']")
        }

        val rawHref = linkElement?.attr("href") ?: return null
        if (!rawHref.contains("/video/") && !rawHref.contains("/play/")) return null
        val href = fixUrlNull(rawHref) ?: return null

        val titleText = this.selectFirst("a.thumb > picture > img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("a.thumb img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("a.n, .n, .v-title, .title, h1, h2, h3, h4")?.text()?.takeIf { it.isNotBlank() }
            ?: linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("a[title]")?.attr("title")?.takeIf { it.isNotBlank() }
            ?: linkElement.text().takeIf { it.isNotBlank() }
            ?: rawHref.substringAfter("/video/").substringBefore("/").replace("+", " ").replace("-", " ").takeIf { it.isNotBlank() }
            ?: return null

        var title = titleText.trim()
        if (title.startsWith("Watch ", ignoreCase = true)) {
            title = title.substring(6).trim()
        }
        title = title.substringBefore(" - SpankBang")
            .substringBefore(" - Free Porn")
            .trim()
        title = fixTitle(title)

        val img = this.selectFirst("a.thumb img, img") ?: linkElement.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-srcset")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
                ?: img?.attr("srcset")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-thumb")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encodedQuery = query.trim().replace(" ", "+")

        for (i in 1..4) {
            val document = getDocument("$mainUrl/s/$encodedQuery/$i/?o=all")
            val results = parseVideos(document)
            val newItems = results.filterNot { item -> searchResponse.any { it.url == item.url } }
            if (newItems.isEmpty()) break
            searchResponse.addAll(newItems)
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)

        val rawTitle = document.selectFirst("h1[title]")?.attr("title")
            ?: document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.title()

        val title = rawTitle
            .removePrefix("Watch")
            .substringBefore(" - SpankBang")
            .substringBefore(" - Free Porn")
            .substringBefore(" - ")
            .trim()

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div#video_container video")?.attr("poster")
        )
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst("div.bottom p:nth-of-type(2)")?.text()?.trim()

        val tags = document.select("div.searches a, div.tags a, a[href*='/tag/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = parseVideos(document).filter { it.url != url }

        return newMovieLoadResponse(if (title.isNotBlank()) title else "Video", url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private fun parseQuality(qualityStr: String): Int {
        return when {
            qualityStr.contains("4k", ignoreCase = true) || qualityStr.contains("2160") -> 2160
            qualityStr.contains("1080") -> 1080
            qualityStr.contains("720") -> 720
            qualityStr.contains("480") -> 480
            qualityStr.contains("360") || qualityStr.contains("320") -> 360
            qualityStr.contains("240") -> 240
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDocument(data)
        val html = document.html()
        val foundUrls = mutableSetOf<String>()

        suspend fun addLink(linkUrl: String, qualityStr: String, isM3u8: Boolean = false) {
            val cleanUrl = fixUrl(linkUrl).replace("\\/", "/")
            if (!cleanUrl.startsWith("http") || foundUrls.contains(cleanUrl)) return
            foundUrls.add(cleanUrl)

            val m3u8 = isM3u8 || cleanUrl.contains(".m3u8")
            val qualityInt = parseQuality(qualityStr)
            val nameStr = if (qualityStr.isNotBlank() && qualityStr.lowercase() != "video" && qualityStr.lowercase() != "unknown") {
                "${this.name} $qualityStr"
            } else {
                this.name
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = nameStr,
                    url = cleanUrl,
                    type = if (m3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = data
                    this.quality = qualityInt
                }
            )
        }

        // 1. stream_url_<quality> JS variables (from yt-dlp)
        val streamUrlMatches = Regex("""stream_url_([a-zA-Z0-9_-]+)\s*=\s*['"]([^'"]+)['"]""").findAll(html)
        for (match in streamUrlMatches) {
            val q = match.groupValues[1]
            val u = match.groupValues[2]
            addLink(u, q, q.contains("m3u8", ignoreCase = true))
        }

        // 2. Direct video sources in HTML (from spankbang-dl)
        val sources = document.select("div#video_container video > source, video > source")
        for (source in sources) {
            val src = source.attr("src").takeIf { it.isNotBlank() } ?: continue
            val q = source.attr("label").takeIf { it.isNotBlank() }
                ?: source.attr("data-quality").takeIf { it.isNotBlank() }
                ?: "Video"
            addLink(src, q)
        }

        // 3. JavaScript stream_data mp4 / qualities (e.g. '720p': ['https://...'] or '720p': 'https://...')
        val streamDataMatches = Regex("""['"](?:4k|2160p|1080p|720p|480p|320p|240p)['"]\s*:\s*\[?\s*['"](https?://[^'"]+)['"]""").findAll(html)
        for (match in streamDataMatches) {
            val u = match.groupValues[1]
            val q = Regex("""(4k|2160p|1080p|720p|480p|320p|240p)""").find(match.value)?.groupValues?.getOrNull(1) ?: "Unknown"
            addLink(u, q)
        }

        // 4. JavaScript m3u8 stream links
        val m3u8Matches = Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""").findAll(html)
        for (match in m3u8Matches) {
            addLink(match.groupValues[1], "HLS", true)
        }

        // 5. Streamkey API endpoint fallback (from yt-dlp)
        if (foundUrls.isEmpty()) {
            val streamKey = Regex("""data-streamkey\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)
                ?: document.selectFirst("[data-streamkey]")?.attr("data-streamkey")

            if (!streamKey.isNullOrBlank()) {
                try {
                    val streamResponse = app.post(
                        "$mainUrl/api/videos/stream",
                        data = mapOf("id" to streamKey, "data" to "0"),
                        headers = mapOf(
                            "Referer" to data,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json, text/javascript, */*; q=0.01"
                        ),
                        cookies = getCookies(data),
                        interceptor = cloudflareKiller
                    ).text

                    val json = JSONObject(streamResponse)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = json.opt(key)
                        if (value is JSONArray) {
                            for (j in 0 until value.length()) {
                                val streamUrl = value.optString(j)
                                if (streamUrl.isNotBlank()) {
                                    addLink(streamUrl, key)
                                }
                            }
                        } else if (value is String && value.isNotBlank()) {
                            addLink(value, key)
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }

        return foundUrls.isNotEmpty()
    }
}