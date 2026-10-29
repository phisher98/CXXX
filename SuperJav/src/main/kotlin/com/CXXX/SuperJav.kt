package com.CXXX

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.VidhideExtractor

class SuperJav : MainAPI() {
    override var mainUrl              = "https://supjav.com"
    override var name                 = "SupJav"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded
    private val subtitleCatUrl        = "https://www.subtitlecat.com"

    // real supjav categories (+ a couple of popular tags); every row paginates via /page/N/
    override val mainPage = mainPageOf(
        "category/censored-jav" to "Censored",
        "category/uncensored-jav" to "Uncensored",
        "category/english-subtitles" to "English Subtitles",
        "category/chinese-subtitles" to "Chinese Subtitles",
        "category/amateur" to "Amateur",
        "category/reducing-mosaic" to "Reducing Mosaic",
        "tag/4k" to "4K",
        "tag/stepmother" to "Step Mother",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        val document = app.get(url).document
        val home = document.select("div.post").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            // keep loading the next /page/N/ as the user scrolls this category
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = this.selectFirst("a[href*='supjav'], a") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null
        val title = linkElem.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("h2, h3, a")?.text()?.takeIf { it.isNotBlank() }
            ?: return null

        val img = this.selectFirst("img")
        // the real thumbnail is in data-original (src is a base64 placeholder)
        val raw = img?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val posterUrl = fixUrlNull(raw)

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encoded = query.trim().replace(" ", "+")

        for (i in 1..3) {
            val url = if (i == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$i/?s=$encoded"
            val document = app.get(url).document
            val results = document.select("div.post").mapNotNull { it.toSearchResult() }
            val unique = results.filterNot { item -> searchResponse.any { it.url == item.url } }
            if (unique.isEmpty()) break
            searchResponse.addAll(unique)
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.archive-title h1, meta[property='og:title']")?.text()?.trim()
            ?: document.title().substringBefore(" - SupJav").trim()
        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.post-meta img")?.attr("src")
        )
        val description = document.selectFirst("div.post-meta h2, meta[property='og:description']")?.text()?.trim()
        val tags = document.select("div.tags a, a[href*='/tag/'], a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select("div.content:contains(You May Also Like) div.posts.clearfix div.post, div.post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        return newMovieLoadResponse(title.trim(), url, TvType.NSFW, url) {
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
        val doc = app.get(data).document

        // each server button carries an encrypted data-link resolved through the supremejav gateway:
        //   supjav.php?l=<data-link>  ->  var OLID='...'  ->  supjav.php?c=<reversed OLID>  ->  302 to the embed
        doc.select("a.btn-server").amap {
            val rawLink = it.attr("data-link")
            if (rawLink.isNotBlank()) {
                runCatching {
                    val gateway = "https://lk1.supremejav.com/supjav.php?l=$rawLink"
                    val gateBody = app.get(gateway, referer = "$mainUrl/").text
                    val olid = Regex("""var OLID = '([^']+)'""").find(gateBody)?.groupValues?.get(1)
                    if (olid != null) {
                        val fetchurl = "https://lk1.supremejav.com/supjav.php?c=${olid.reversed()}"
                        val sourcefetch = app.get(fetchurl, referer = gateway, allowRedirects = false)
                            .headers["location"].orEmpty()
                        if (sourcefetch.isNotBlank()) {
                            loadExtractor(sourcefetch, referer = "$mainUrl/", subtitleCallback, callback)
                        }
                    }
                }
            }
        }

        doc.select("iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (!src.contains("google") && !src.contains("facebook")) {
                runCatching {
                    loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        try {
            val title = doc.select("head > title").text()
            val javCode = "([a-zA-Z]+-\\d+)".toRegex().find(title)?.groups?.get(1)?.value
            if (!javCode.isNullOrEmpty()) {
                val query = "$subtitleCatUrl/index.php?search=$javCode"
                val subDoc = app.get(query, timeout = 15).document
                val subList = subDoc.select("td a")
                for (item in subList) {
                    if (item.text().contains(javCode, ignoreCase = true)) {
                        val fullUrl = "$subtitleCatUrl/${item.attr("href")}"
                        val pDoc = app.get(fullUrl, timeout = 10).document
                        val sList = pDoc.select(".col-md-6.col-lg-4")
                        for (subItem in sList) {
                            try {
                                val language = subItem.select(".sub-single span:nth-child(2)").text()
                                val text = subItem.select(".sub-single span:nth-child(3) a")
                                if (text.isNotEmpty() && text[0].text() == "Download") {
                                    val subUrl = "$subtitleCatUrl${text[0].attr("href")}"
                                    subtitleCallback.invoke(
                                        newSubtitleFile(
                                            language.replace("👍 👎", "").trim(),
                                            subUrl
                                        )
                                    )
                                }
                            } catch (_: Exception) { }
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        return true
    }
}

class watchadsontape : StreamTape() {
    override var mainUrl = "https://watchadsontape.com"
    override var name = "StreamTape"
}

/** LUC server - luminscdn.xyz uses the jw8 / new100 StreamWish/Vidhide player family. */
class Luminscdn : VidhideExtractor() {
    override var mainUrl = "https://luminscdn.xyz"
    override var name = "Luminscdn"
    override val requiresReferer = false
}

open class EmturbovidExtractor : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(
            url, referer = referer ?: "$mainUrl/"
        ).document.select("#video_player").attr("data-hash")
        val sources = mutableListOf<ExtractorLink>()
        if (response.isNotBlank()) {
            sources.add(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = response,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return sources
    }
}

class fc2stream: VidhideExtractor() {
    override var mainUrl = "https://fc2stream.tv"
    override val requiresReferer = false
}
