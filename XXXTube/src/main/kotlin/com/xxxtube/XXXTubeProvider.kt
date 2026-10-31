package com.xxxtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class XXXTubeProvider : MainAPI() {
    override var mainUrl = "https://x-x-x.tube"
    override var name = "xxx-tube"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW,
        TvType.Movie,
        TvType.TvSeries
    )

    private val cfInterceptor by lazy { CloudflareKiller() }

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Cookie" to "kt_tcookie=1; kt_is_visited=1"
    )

    // No categories on the home page — the site's category / model / album cards are not
    // playable videos, so they are kept out of every list (see toSearchResult).
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Featured & Top Videos",
        "$mainUrl/latest-updates" to "Latest Updates",
        "$mainUrl/top-rated" to "Top Rated",
        "$mainUrl/most-popular" to "Most Popular"
    )

    private suspend fun requestDoc(url: String, referer: String = "$mainUrl/"): Document {
        val res = app.get(
            url,
            headers = defaultHeaders + ("Referer" to referer)
        )
        if (res.code in listOf(403, 503) || res.text.contains("cdn-cgi/challenge-platform")) {
            return app.get(
                url,
                headers = defaultHeaders + ("Referer" to referer),
                interceptor = cfInterceptor
            ).document
        }
        return res.document
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val isRootHome = request.data.trimEnd('/') == mainUrl
        if (isRootHome && page > 1) {
            return newHomePageResponse(emptyList(), false)
        }

        val pageUrl = when {
            isRootHome -> "$mainUrl/"
            page <= 1 -> "${request.data.trimEnd('/')}/"
            else -> "${request.data.trimEnd('/')}/$page/"
        }

        val document = requestDoc(pageUrl)

        if (isRootHome) {
            val homeSections = mutableListOf<HomePageList>()
            document.select("div.section").forEach { section ->
                val sectionTitle = section.selectFirst("div.heading .title, h1, h2")?.text()?.trim()
                    ?: return@forEach
                // Drop the category rows entirely; anything left with no playable card is skipped below.
                if (sectionTitle.contains("categor", ignoreCase = true)) return@forEach
                val cards = section.select("div.thumb-inner").mapNotNull { it.toSearchResult() }
                if (cards.isNotEmpty()) {
                    homeSections.add(
                        HomePageList(
                            name = sectionTitle,
                            list = cards,
                            isHorizontalImages = true
                        )
                    )
                }
            }
            if (homeSections.isNotEmpty()) {
                return newHomePageResponse(homeSections, false)
            }
        }

        val items = document.select("div.thumb-inner").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        val hasNext = document.select("div.pagination div.item a").any { el ->
            val href = el.attr("href")
            href.contains("/${page + 1}/")
        } || items.size >= 24

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("a[href]") ?: return null
        val rawHref = linkEl.attr("href").trim()
        if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.startsWith("javascript")) return null
        val href = fixUrl(rawHref)

        // Videos only. Category / model / album cards are not playable, so they never become a
        // result — this is what keeps categories off the home page and out of search.
        if (!href.contains("/videos/")) return null

        val imgEl = this.selectFirst("div.img img, img")
        val title = (
            this.selectFirst("div.title, .info-thumb .title")?.text()
                ?: linkEl.attr("title").ifBlank { null }
                ?: imgEl?.attr("alt")?.ifBlank { null }
            )?.trim() ?: return null

        val posterUrl = imgEl?.let { img ->
            val rawImg = img.attr("data-original")
                .ifBlank { img.attr("data-src") }
                .ifBlank { img.attr("src") }
            if (rawImg.isNotBlank() && !rawImg.startsWith("data:")) fixUrlNull(rawImg) else null
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    /**
     * Only the long-standing search(query) API is used: the paginated overload returns
     * SearchResponseList, a type missing from older Cloudstream builds, and a provider that
     * declares it cannot be loaded there at all. Two pages are merged instead.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val first = searchPage(query, 1)
        if (first.isEmpty()) return first
        val second = runCatching { searchPage(query, 2) }.getOrNull().orEmpty()
        return (first + second).distinctBy { it.url }
    }

    /**
     * Paginated search. The site pages as /search/{query}/ , /search/{query}/2/ , /search/{query}/3/ …
     * (the capture has pages 1, 2 and 4). We keep going until a page comes back empty, so no
     * dependency on the pagination markup.
     */

    private suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (encodedQuery.isBlank()) return emptyList()

        val searchUrl = if (page <= 1) {
            "$mainUrl/search/$encodedQuery/"
        } else {
            "$mainUrl/search/$encodedQuery/$page/"
        }
        val document = runCatching { requestDoc(searchUrl) }.getOrNull() ?: return emptyList()

        return document.select("div.item.thumb div.thumb-inner, div.thumb-inner")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = requestDoc(url)

        if (url.contains("/categories/") || !url.contains("/videos/")) {
            val rawTitle = document.selectFirst("h1, div.heading .title")?.text()?.trim()
                ?: document.selectFirst("title")?.text()?.substringBefore("|")?.trim()
                ?: "Category"
            val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

            val episodes = mutableListOf<Episode>()
            val seenUrls = mutableSetOf<String>()

            for (page in 1..2) {
                val pageDoc = if (page == 1) {
                    document
                } else {
                    val nextUrl = "${url.trimEnd('/')}/$page/"
                    runCatching { requestDoc(nextUrl, url) }.getOrNull() ?: break
                }

                val cards = pageDoc.select("div.thumb-inner")
                if (cards.isEmpty()) break

                for (card in cards) {
                    val a = card.selectFirst("a[href*=/videos/]") ?: continue
                    val epUrl = fixUrl(a.attr("href"))
                    if (!seenUrls.add(epUrl)) continue

                    val imgEl = card.selectFirst("div.img img, img")
                    val epTitle = (
                        card.selectFirst("div.title, .info-thumb .title")?.text()
                            ?: a.attr("title").ifBlank { null }
                            ?: imgEl?.attr("alt")
                        )?.trim() ?: "Video ${episodes.size + 1}"

                    val epPoster = imgEl?.let {
                        val src = it.attr("data-original").ifBlank { it.attr("data-src") }.ifBlank { it.attr("src") }
                        if (src.isNotBlank() && !src.startsWith("data:")) fixUrlNull(src) else null
                    }

                    val durationText = card.selectFirst("div.duration, span.duration")?.text()?.trim()

                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = if (!durationText.isNullOrBlank()) "$epTitle ($durationText)" else epTitle
                            this.episode = episodes.size + 1
                            this.posterUrl = epPoster
                        }
                    )
                }
            }

            val mainPoster = episodes.firstOrNull()?.posterUrl
                ?: fixUrlNull(document.selectFirst(".thumb img")?.attr("src"))

            return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = mainPoster
                this.plot = description
            }
        }

        val title = document.selectFirst("h1, div.heading .title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.selectFirst("title")?.text()
                ?.removePrefix("Watch ")
                ?.substringBefore(" XXX Porn")
                ?.substringBefore("|")
                ?.trim()
            ?: return null

        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst(".aside .model_description")?.text()?.trim()

        val html = document.outerHtml()
        val previewImgFromFlashvars = Regex("""preview_url\s*:\s*['"]([^'"]+)['"]""")
            .find(html)?.groupValues?.get(1)
        val videoId = Regex("""/videos/(\d+)/""").find(url)?.groupValues?.get(1)
        val computedPoster = videoId?.let { id ->
            val folder = (id.toLongOrNull()?.div(1000)?.times(1000)) ?: 0L
            "$mainUrl/contents/videos_screenshots/$folder/$id/previewpaid.mp4.jpg"
        }

        val poster = fixUrlNull(
            previewImgFromFlashvars
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".player-inner img, .thumb img")?.attr("src")
                ?: computedPoster
        )

        val tags = buildList {
            document.selectFirst("meta[name=keywords]")?.attr("content")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() && !it.equals("XXX Tube", ignoreCase = true) }
                ?.let { addAll(it) }
            document.select("a[href*=/categories/], a[href*=/tags/], a[href*=/models/]").forEach { a ->
                val t = a.text().trim()
                if (t.isNotBlank() && t.length < 35) add(t)
            }
        }.distinct()

        val recommendations = document.select("div.thumb-inner").mapNotNull {
            it.toSearchResult()
        }.filter { it.url != url }.distinctBy { it.url }

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
        val document = requestDoc(data, "$mainUrl/")
        val html = document.outerHtml()
        var foundLinks = false

        val licenseCode = Regex("""license_code\s*:\s*['"]([^'"]+)['"]""")
            .find(html)?.groupValues?.get(1)

        val urlKeys = listOf(
            "video_url" to "video_url_text",
            "video_alt_url" to "video_alt_url_text",
            "video_alt_url2" to "video_alt_url2_text",
            "video_alt_url3" to "video_alt_url3_text"
        )

        for ((urlKey, labelKey) in urlKeys) {
            val rawVideoUrl = Regex("""$urlKey\s*:\s*['"]([^'"]+)['"]""")
                .find(html)?.groupValues?.get(1)?.trim() ?: continue
            if (rawVideoUrl.isBlank() || rawVideoUrl.contains("spicy-box.mp4")) continue

            val qualityLabel = Regex("""$labelKey\s*:\s*['"]([^'"]+)['"]""")
                .find(html)?.groupValues?.get(1)?.trim() ?: "HD"

            val unlockedUrl = if (rawVideoUrl.startsWith("function/0/")) {
                kvsDecryptUrl(rawVideoUrl, licenseCode)
            } else {
                rawVideoUrl
            }

            val finalUrl = fixUrl(unlockedUrl)
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} ($qualityLabel)",
                    url = finalUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = data
                    this.quality = getQualityFromName(qualityLabel)
                    this.headers = mapOf(
                        "Referer" to data,
                        "Cookie" to "kt_tcookie=1; kt_is_visited=1"
                    )
                }
            )
            foundLinks = true
        }

        val getFileRegex = Regex("""(https?://[^'"\s>]+/get_file/[^'"\s>]+|/get_file/[^'"\s>]+)""")
        getFileRegex.findAll(html).map { it.value.trimEnd('\\') }.distinct().forEach { matchUrl ->
            if (matchUrl.contains("spicy-box.mp4")) return@forEach
            val cleanUrl = if (matchUrl.startsWith("function/0/")) {
                kvsDecryptUrl(matchUrl, licenseCode)
            } else {
                matchUrl
            }
            val fullUrl = fixUrl(cleanUrl)
            val label = when {
                fullUrl.contains("paid.mp4") -> "Full HD"
                fullUrl.contains("trailer") || fullUrl.contains("preview") -> "Preview"
                else -> "Stream"
            }
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - $label",
                    url = fullUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = data
                    this.quality = if (label == "Full HD") Qualities.P720.value else Qualities.P480.value
                    this.headers = mapOf(
                        "Referer" to data,
                        "Cookie" to "kt_tcookie=1; kt_is_visited=1"
                    )
                }
            )
            foundLinks = true
        }

        document.select("video source[src], video[src]").forEach { el ->
            val src = el.attr("src").trim()
            if (src.isNotBlank() && !src.contains("spicy-box.mp4")) {
                val fullUrl = fixUrl(src)
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} Direct",
                        url = fullUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Referer" to data,
                            "Cookie" to "kt_tcookie=1; kt_is_visited=1"
                        )
                    }
                )
                foundLinks = true
            }
        }

        return foundLinks
    }

    private fun kvsDecryptUrl(rawUrl: String, licenseCode: String?): String {
        if (!rawUrl.startsWith("function/0/")) return rawUrl
        val urlPart = rawUrl.removePrefix("function/0/")
        if (licenseCode.isNullOrBlank()) return urlPart

        val parts = urlPart.split("/").toMutableList()
        val getFileIndex = parts.indexOf("get_file")
        if (getFileIndex == -1 || getFileIndex + 2 >= parts.size) return urlPart

        val hashToken = parts[getFileIndex + 2]
        if (hashToken.length < 32) return urlPart

        val hash32 = hashToken.substring(0, 32).toCharArray()
        val tail = hashToken.substring(32)

        val cleanLicense = licenseCode.filter { it.isDigit() }
            .map { if (it == '0') 1 else it.digitToInt() }
        if (cleanLicense.isEmpty()) return urlPart

        val doubleLicense = (cleanLicense + cleanLicense)
        val indices = IntArray(32) { i ->
            var sum = 0
            for (k in 0..i) {
                sum += doubleLicense[k % doubleLicense.size]
            }
            (sum + i) % 32
        }

        for (i in 31 downTo 0) {
            val swapIdx = indices[i]
            val tmp = hash32[i]
            hash32[i] = hash32[swapIdx]
            hash32[swapIdx] = tmp
        }

        parts[getFileIndex + 2] = String(hash32) + tail
        return parts.joinToString("/")
    }
}
