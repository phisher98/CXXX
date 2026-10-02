package com.jacekun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.json.JSONObject

class JavMost : MainAPI() {
    private val globaltvType = TvType.NSFW

    override var name = "JavMost"
    override var mainUrl = "https://www.javmost.ws"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = true
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val vpnStatus = VPNStatus.MightBeNeeded

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val document = app.get(url).document
        val all = ArrayList<HomePageList>()

        val cards = document.select("div#content-update div.card, div.card").mapNotNull { card ->
            val linkA = card.selectFirst("center > a, div.card-block > a, a") ?: return@mapNotNull null
            val href = fixUrlNull(linkA.attr("href")) ?: return@mapNotNull null

            val title = card.selectFirst("h1.card-title, h4.card-title, a[alt]")?.text()?.takeIf { it.isNotBlank() }
                ?: linkA.attr("alt").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val img = card.selectFirst("img")
            val image = fixUrlNull(
                img?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            )

            val yearText = card.selectFirst("div.card-block p")?.text()
            val year = yearText?.let { Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.value?.toIntOrNull() }

            newMovieSearchResponse(title, href, globaltvType) {
                this.posterUrl = image
                this.year = year
            }
        }.distinctBy { it.url }

        if (cards.isNotEmpty()) {
            all.add(HomePageList("Latest Updates", cards, isHorizontalImages = true))
        }

        return newHomePageResponse(all, hasNext = cards.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encoded = query.trim().replace(" ", "+")

        for (page in 1..3) {
            val url = if (page == 1) "$mainUrl/search/$encoded/" else "$mainUrl/search/$encoded/page/$page/"
            val document = app.get(url).document
            val results = document.select("div#content-update div.card, div.card").mapNotNull { card ->
                val linkA = card.selectFirst("center > a, div.card-block > a, a") ?: return@mapNotNull null
                val href = fixUrlNull(linkA.attr("href")) ?: return@mapNotNull null

                val title = card.selectFirst("h1.card-title, h4.card-title, a[alt]")?.text()?.takeIf { it.isNotBlank() }
                    ?: linkA.attr("alt").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val img = card.selectFirst("img")
                val image = fixUrlNull(
                    img?.attr("data-src")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                )

                val yearText = card.selectFirst("div.card-block p")?.text()
                val year = yearText?.let { Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.value?.toIntOrNull() }

                newMovieSearchResponse(title, href, globaltvType) {
                    this.posterUrl = image
                    this.year = year
                }
            }
            val unique = results.filterNot { item -> searchResponse.any { it.url == item.url } }
            if (unique.isEmpty()) break
            searchResponse.addAll(unique)
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val html = document.html()

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.card-block img, img.card-img-top")?.attr("src")
        )
        val title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: document.title().substringBefore(" - Watch").trim()
        val descript = document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        val tags = document.select("a[href*='/genre/'], a[href*='/tag/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = document.select("a[href*='/star/'], a[href*='/actress/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select("div.card")
            .mapNotNull { card ->
                val linkA = card.selectFirst("center > a, a") ?: return@mapNotNull null
                val href = fixUrlNull(linkA.attr("href")) ?: return@mapNotNull null
                val name = card.selectFirst("h1, h4, a[alt]")?.text() ?: return@mapNotNull null
                newMovieSearchResponse(name, href, globaltvType)
            }
            .distinctBy { it.url }
            .filter { it.url != url }

        // Fetch streaming embeds using JavMost AJAX endpoint
        val streamUrls = mutableListOf<String>()
        runCatching {
            val y1 = Regex("""var\s+YWRzMQo\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            val y2 = Regex("""var\s+YWRzMg\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            val y4 = Regex("""var\s+YWRzNA\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            val y5 = Regex("""var\s+YWRzNQ\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            val y6 = Regex("""var\s+YWRzNg\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)

            val endpointPath = Regex("""url_source\s*=\s*YREdIr\s*\+\s*'([^']+)'""").find(html)?.groupValues?.get(1) ?: "ri3123o235r/"
            val endpoint = "$mainUrl/$endpointPath"

            if (y1 != null && y2 != null && y4 != null && y5 != null && y6 != null) {
                val ajaxRes = app.post(
                    endpoint,
                    headers = mapOf(
                        "Referer" to url,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    data = mapOf(
                        "group" to y2,
                        "part" to "1",
                        "code" to y4,
                        "code2" to y5,
                        "code3" to y6,
                        "value" to y1,
                        "sound" to "av"
                    )
                ).text

                val jsonObj = JSONObject(ajaxRes)
                val dataArr = jsonObj.optJSONArray("data")
                if (dataArr != null) {
                    for (i in 0 until dataArr.length()) {
                        val link = dataArr.optString(i)
                        if (link.isNotBlank()) {
                            streamUrls.add(link)
                        }
                    }
                }
            }
        }

        // Also check iframes in page
        document.select("iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (!src.contains("google") && !src.contains("facebook")) {
                streamUrls.add(src)
            }
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = globaltvType,
            dataUrl = streamUrls.distinct().toJson(),
        ).apply {
            this.apiName = this@JavMost.name
            this.posterUrl = poster
            this.plot = descript
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = runCatching { data.fromJson<List<String>>() }.getOrNull()
            ?: if (data.startsWith("http")) listOf(data) else emptyList()

        for (link in urls) {
            runCatching {
                loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
            }
        }
        return true
    }

    companion object {
        private val gson = Gson()
        private inline fun <reified T> String.fromJson(): T =
            gson.fromJson(this, object : TypeToken<T>() {}.type)
    }
}