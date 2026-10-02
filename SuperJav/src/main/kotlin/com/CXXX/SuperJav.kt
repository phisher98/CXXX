package com.CXXX

import com.lagradost.api.Log
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

    override val mainPage = mainPageOf(
        "category/censored-jav" to "Censored Jav",
        "category/english-subtitles" to "English Jav",
        "tag/4k" to "4K",
        "tag/stepmother" to "Step Mother",
        "tag/tits" to "Tits",
        "reducing-mosaic" to "Reducing Mosaic",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        val document = app.get(url).document
        val home = document.select("div.post").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home.distinctBy { it.url },
                isHorizontalImages = true
            ),
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
        val posterUrl = fixUrlNull(
            img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

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
            document.selectFirst("div.post-meta img, meta[property='og:image']")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
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

        doc.select("a.btn-server").amap {
            val rawLink = it.attr("data-link")
            if (rawLink.isNotBlank()) {
                runCatching {
                    val id = rawLink.toCharArray().reversed().joinToString("")
                    val fetchurl = "https://lk1.supremejav.com/supjav.php?c=$id"
                    val sourcefetch = app.get(fetchurl, referer = fetchurl, allowRedirects = false).headers["location"].orEmpty()
                    if (sourcefetch.isNotBlank()) {
                        Log.d("Phisher", sourcefetch)
                        loadExtractor(sourcefetch, referer = "$mainUrl/", subtitleCallback, callback)
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
                                            language.replace("\uD83D\uDC4D \uD83D\uDC4E", "").trim(),
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
