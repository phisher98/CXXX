@file:Suppress("NAME_SHADOWING")

package com.xprimehub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element
import java.net.URI

class XPrimeHub : MainAPI() {
    override var mainUrl: String = runBlocking {
        XPrimeHubProvider.getDomains()?.xprimehub ?: "https://xprimehub.skin"
    }
    override var name                 = "XPrimeHub"
    override val hasMainPage          = true
    override var lang                 = "hi"
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "/" to "Home",
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
        val document = app.get("$mainUrl/${request.data}/page/$page").document
        val home     = document.select(".elementor-loop-container .e-loop-item").mapNotNull { it.toSearchResult() }

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
        val title     =this.select("h3").text().substringAfter("[18+]").substringBefore("UNRATED")
        val href      =this.select("a").attr("href")
        val posterUrl = this.select("img").attr("data-lazy-src").takeIf { it.startsWith("http") }
            ?: this.select("img").attr("src").takeIf { it.startsWith("http") }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String,page: Int): SearchResponseList {
        val document = app.get("${mainUrl}/page/$page/?s=$query").document
        val results = document.select(".elementor-loop-container .e-loop-item").mapNotNull { it.toSearchResult() }
        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("head title")
            ?.text()
            ?.substringAfter("[18+]")
            ?.substringBefore("UNRATED")?.substringBefore("Series")
            ?.trim() ?: "No Title"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val bgposter = fixUrlNull(
            document.selectFirst("p:nth-child(14) > img:nth-child(1)")?.attr("data-lazy-src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document.selectFirst(
            "div.elementor-element.elementor-element-78548525.elementor-widget.elementor-widget-theme-post-content > p:nth-child(12)"
        )?.text()?.trim()

        val episodesList = mutableListOf<Episode>()

        val episodeBlocks = document.select("h5")

        episodeBlocks.forEach { ep ->
            val rawText = ep.text()

            val cleanName = rawText.replace("To", "-").trim()

            val container = ep.nextElementSibling()
            val buttons = container?.select("a:has(button)") ?: emptyList()

            val links = buttons.mapNotNull { a ->
                val link = a.attr("href")
                link.ifEmpty { null }
            }

            if (links.isNotEmpty()) {
                val data = links.joinToString(",")
                episodesList.add(
                    newEpisode(data) {
                        this.name = cleanName
                    }
                )
            }
        }

        // ===== fallback =====
        if (episodesList.isEmpty()) {
            val buttons = document.select("button.btn")

            buttons.forEach { button ->
                val parentA = button.closest("a")
                val link = parentA?.attr("href") ?: return@forEach

                episodesList.add(
                    newEpisode(link) {
                        this.name = button.text()
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
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

        val excludedButtonTexts = setOf("Filepress", "GDToT", "DropGalaxy")

        data.split(",").forEach { link ->
            if (link.isBlank()) return@forEach

            val doc = app.get(link).document

            val detailPageUrls = doc.select("button.btn")
                .filterNot { button ->
                    excludedButtonTexts.any { button.text().contains(it, ignoreCase = true) }
                }
                .mapNotNull { it.closest("a")?.attr("href")?.takeIf(String::isNotBlank) }

            detailPageUrls.forEach { streamingUrl ->
                loadExtractor(streamingUrl, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
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
        if (urlValue.isEmpty()) return

        val document = runCatching { app.get(urlValue).document }.getOrNull() ?: return
        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        val labelExtras = buildString {
            if (header.isNotEmpty()) append(header)
            if (size.isNotEmpty()) append(" [$size]")
        }

        val div = document.selectFirst("div.card-body") ?: return

        div.select("h2 a.btn").amap {

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

                text.contains("BuzzServer") -> {
                    val dlink = app.get("$link/download", referer = link, allowRedirects = false).headers["hx-redirect"] ?: ""
                    val baseUrl = getBaseUrl(link)
                    if (dlink.isNotEmpty()) {
                        callback.invoke(
                            newExtractorLink(
                                "BuzzServer",
                                "[BuzzServer] $labelExtras",
                                baseUrl + dlink,
                            ) { this.quality = quality }
                        )
                    } else {
                        Log.w("Error:", "Not Found")
                    }
                }

                text.contains("pixeldra", ignoreCase = true) || text.contains("pixel", ignoreCase = true) || text.contains("PixeLServer", ignoreCase = true) -> {
                    val baseUrlLink = getBaseUrl(link)
                    val finalURL = if (link.contains("download", true)) link
                    else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"

                    callback(
                        newExtractorLink(
                            "Pixeldrain",
                            "[Pixeldrain] $labelExtras",
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

                /*
                text.contains("10Gbps", ignoreCase = true) -> {
                    var currentLink = link
                    var redirectUrl: String?

                    while (true) {
                        val response = app.get(currentLink, allowRedirects = false)
                        redirectUrl = response.headers["location"]
                        if (redirectUrl == null) {
                            Log.e("HubCloud", "10Gbps: No redirect")
                            return@amap
                        }
                        if ("link=" in redirectUrl) break
                        currentLink = redirectUrl
                    }
                    val finalLink = redirectUrl.substringAfter("link=")
                    callback.invoke(
                        newExtractorLink(
                            "10Gbps [Download]",
                            "10Gbps [Download] $labelExtras",
                            finalLink,
                        ) { this.quality = quality }
                    )
                }
                */

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