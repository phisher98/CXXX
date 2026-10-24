package com.jacekun

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class JavFreeProvider : MainAPI() {
    private val globalTvType = TvType.NSFW
    override var name = "JavFree"
    override var mainUrl = "https://javfree.sh"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val vpnStatus = VPNStatus.MightBeNeeded

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl).document
        val all = ArrayList<HomePageList>()

        document.getElementsByTag("body").select("div#page")
            .select("div#content").select("div#primary")
            .select("main")
            .select("section").forEach { it2 ->
                val title = it2?.select("h2.widget-title")?.text() ?: "Unnamed Row"
                val inner = it2.select("div.videos-list article, article")

                val elements: List<SearchResponse> = inner.mapNotNull {
                    val aa = it.select("a").firstOrNull() ?: return@mapNotNull null
                    val link = fixUrlNull(aa.attr("href")) ?: return@mapNotNull null
                    val name = aa.attr("title").takeIf { it.isNotBlank() }
                        ?: it.selectFirst("h3, h2, a")?.text()
                        ?: "<No Title>"

                    val img = aa.selectFirst("img")
                    val image = fixUrlNull(
                        img?.attr("data-src")?.takeIf { it.isNotBlank() }
                            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                            ?: aa.selectFirst("video")?.attr("poster")
                    )

                    newMovieSearchResponse(
                        name = name,
                        url = link,
                        type = globalTvType,
                    ).apply {
                        this.posterUrl = image
                    }
                }

                if (elements.isNotEmpty()) {
                    all.add(
                        HomePageList(
                            name = title,
                            list = elements.distinctBy { it.url },
                            isHorizontalImages = true
                        )
                    )
                }
            }
        return newHomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.trim().replace(" ", "+")
        val searchUrl = "$mainUrl/search/movie/$encodedQuery"
        val document = app.get(searchUrl).document
            .select("div.videos-list article, article[id^=post], article")

        return document.mapNotNull {
            val aa = it?.select("a")?.firstOrNull() ?: return@mapNotNull null
            val url = fixUrlNull(aa.attr("href")) ?: return@mapNotNull null
            val title = aa.attr("title").takeIf { it.isNotBlank() }
                ?: it.selectFirst("h3, h2, a")?.text()
                ?: return@mapNotNull null

            val img = aa.selectFirst("img")
            val image = fixUrlNull(
                img?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: aa.selectFirst("video")?.attr("poster")
            )

            newMovieSearchResponse(
                name = title,
                url = url,
                type = globalTvType,
            ).apply {
                this.posterUrl = image
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val poster = fixUrlNull(doc.select("meta[property=og:image]").firstOrNull()?.attr("content"))
        val title = doc.select("meta[name=title]").firstOrNull()?.attr("content")?.cleanText()
            ?: doc.select("meta[property=og:title]").firstOrNull()?.attr("content")?.cleanText()
            ?: doc.title().cleanText()
        val descript = doc.select("meta[name=description]").firstOrNull()?.attr("content")?.cleanText()

        val body = doc.getElementsByTag("body")
        val yearElem = body
            .select("div#page > div#content > div#primary > main > article")
            .select("div.entry-content > div.tab-content > div#video-about > div#video-date")
        val year = yearElem.text().trim().takeLast(4).toIntOrNull()

        // Safely extract iframe src using regex or selector
        val pageHtml = doc.html()
        val streamUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(pageHtml)?.groupValues?.getOrNull(1)
            ?: doc.selectFirst("iframe[src]")?.attr("src").orEmpty()

        val tags = doc.select("div#video-tags a, a[href*='/tag/'], a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = doc.select("div.videos-list article, article")
            .mapNotNull {
                val aa = it.selectFirst("a") ?: return@mapNotNull null
                val link = fixUrlNull(aa.attr("href")) ?: return@mapNotNull null
                val name = aa.attr("title").takeIf { it.isNotBlank() } ?: it.text().trim()
                newMovieSearchResponse(name, link, globalTvType)
            }
            .distinctBy { it.url }
            .filter { it.url != url }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = globalTvType,
            dataUrl = streamUrl,
        ).apply {
            this.apiName = this@JavFreeProvider.name
            this.posterUrl = poster
            this.year = year
            this.plot = descript
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
        try {
            if (data.isBlank()) return false

            if (data.contains("player.javfree.sh")) {
                val id = if (data.contains("#")) data.substringAfter("#") else data.substringAfterLast("/")
                val linkToGet = "https://player.javfree.sh/stream/$id"
                val jsonres = app.get(linkToGet, referer = mainUrl).text
                val referer = "https://player.javfree.sh/embed.html"

                tryParseJson<ResponseJson?>(jsonres)?.let { item ->
                    item.list?.forEach { link ->
                        val linkUrl = link.file ?: ""
                        if (linkUrl.isNotBlank()) {
                            loadExtractor(
                                url = linkUrl,
                                referer = referer,
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )
                        }
                    }
                    return true
                }
            } else if (data.startsWith("http")) {
                loadExtractor(data, "$mainUrl/", subtitleCallback, callback)
                return true
            }
        } catch (e: Exception) {
            logError(e)
        }
        return false
    }

    private data class ResponseJson(
        @JsonProperty("list") val list: List<ResponseData>? = null
    )
    private data class ResponseData(
        @JsonProperty("url") val file: String? = null,
        @JsonProperty("server") val server: String? = null,
        @JsonProperty("active") val active: Int? = null
    )

    private fun String.cleanText() : String = this.trim().removePrefix("Watch JAV Free")
        .removeSuffix("HD Free Online on JAVFree.SH").trim()
        .removePrefix("Watch JAV").trim()
}