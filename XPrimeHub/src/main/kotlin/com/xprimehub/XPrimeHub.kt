package com.xprimehub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class XPrimeHub : MainAPI() {
    override var mainUrl              = "https://xprimehub.vip"
    override var name                 = "XPrimeHub"
    override val hasMainPage          = true
    override var lang                 = "hi"
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "/" to "Home",
        "c/ullu-originals" to "Ullu",
        "c/bindastimes" to "Bindastimes",
        "c/kooku" to "Kooku",
        "c/primeshots" to "PrimeShots",
        "c/primeflix" to "Primeflix",
        "c/rabbit" to "Rabbit",
        "c/onlyfans" to "OnlyFans",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/page/$page").document
        val home     = document.select("div.bw_thumb_title").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title     =this.select("h1").text().substringAfter("[18+]").substringBefore("UNRATED")
        val href      =this.select("a").attr("href")
        val posterUrl = this.select("div.bw_thumb img").attr("src").takeIf { it.startsWith("http") }
            ?: this.select("div.bw_thumb img").attr("data-src").takeIf { it.startsWith("http") }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/search/$query").document
        val results = document.select("div.bw_thumb_title").mapNotNull { it.toSearchResult() }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("head title")?.text()?.substringAfter("[18+]")?.substringBefore("UNRATED") ?: "No Title"
        val poster = fixUrlNull(document.selectFirst("div > div.bw_desc > p:nth-child(8) > img:nth-child(1)")?.attr("src"))
        val description = document.selectFirst("div > div.bw_desc > p:nth-child(6)")?.text()?.trim()
        val buttons = document.select("button.btn")
        val href = buttons.mapNotNull { button ->
            val ahref = button.parents().select("a").attr("href")
            val buttonText = button.text()
            val resolution = when {
                "720p" in buttonText -> "720p"
                "1080p" in buttonText -> "1080p"
                else -> "Unknown"
            }
            if (ahref.isNotEmpty()) LoadLinks(resolution, ahref) else null
        }
        return newMovieLoadResponse(title, url, TvType.TvSeries, href) {
            this.posterUrl = poster
            this.plot = description
        }
    }


    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val excludedButtonTexts = setOf("Filepress", "GDToT", "DropGalaxy")
        parseJson<ArrayList<LoadLinks>>(data).amap { loadLink ->
            val doc = app.get(loadLink.sourceLink).document
            val detailPageUrls = doc.select("button.btn")
                .filterNot { button -> excludedButtonTexts.any { button.text().contains(it, ignoreCase = true) } }
                .mapNotNull { it.closest("a")?.attr("href")?.takeIf(String::isNotBlank) }
            detailPageUrls.forEach { streamingUrl ->
                loadExtractor(streamingUrl, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }
    data class LoadLinks (
        @JsonProperty("sourceQuality") val sourceName: String,
        @JsonProperty("sourceLink") val sourceLink: String
    )
}

class VCloud : ExtractorApi() {
    override val name = "V-Cloud"
    override val mainUrl = "https://vcloud.lol"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var href = url
        if (href.contains("api/index.php")) {
            href = app.get(url).document.selectFirst("div.main h4 a")?.attr("href") ?: ""
        }

        val doc = app.get(href).document
        val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
        val urlValue = Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""

        if (urlValue.isNotEmpty()) {
            val document = app.get(urlValue).document
            val size = document.selectFirst("i#size")?.text() ?: ""
            val header = document.selectFirst("div.card-header")?.text() ?: ""
            val headerDetails =
                """\.\d{3,4}p\.(.*)-[^-]*${'$'}""".toRegex().find(header)?.groupValues?.get(1)?.trim() ?: ""

            document.selectFirst("div.card-body")?.select("h2 a.btn")
                ?.filterNot { it.text().contains("Telegram", ignoreCase = true) }
                ?.forEach { linkElement ->
                    val link = linkElement.attr("href")

                    when {
                        link.contains("technorozen.workers.dev") -> {
                            val href = app.get(link).document.selectFirst("#vd")?.attr("href") ?: ""
                            callback.invoke(
                                newExtractorLink(
                                    source = "V-Cloud 10 Gbps",
                                    name = "V-Cloud 10 Gbps $size",
                                    url = href
                                ) {
                                    this.referer = ""
                                    this.quality = getIndexQuality(header)
                                }
                            )
                        }
                        link.contains("pixeldra") -> {
                            callback.invoke(
                                newExtractorLink(
                                    source = "Pixeldrain",
                                    name = "Pixeldrain $size",
                                    url = link
                                ) {
                                    this.referer = ""
                                    this.quality = getIndexQuality(header)
                                }
                            )
                        }
                        link.contains("dl.php") -> {
                            val response = app.get(link, allowRedirects = false)
                            val downloadLink = response.headers["location"].toString().split("link=").getOrNull(1) ?: link
                            callback.invoke(
                                newExtractorLink(
                                    source = "V-Cloud[Download]",
                                    name = "V-Cloud[Download] $size",
                                    url = downloadLink
                                ) {
                                    this.referer = ""
                                    this.quality = getIndexQuality(header)
                                }
                            )
                        }
                        listOf(".dev", ".hubcdn.xyz", ".lol").any { link.contains(it) } -> {
                            val label = when {
                                link.contains(".lol") -> "V-Cloud [FSL]"
                                else -> "V-Cloud"
                            }
                            callback.invoke(
                                newExtractorLink(
                                    source = label,
                                    name = "$label $size",
                                    url = link
                                ) {
                                    this.referer = ""
                                    this.quality = getIndexQuality(header)
                                }
                            )
                        }
                        else -> loadExtractor(link, subtitleCallback, callback)
                    }
                }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}