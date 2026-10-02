package com.jacekun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class JavGuru : MainAPI() {
    private val globaltvType = TvType.NSFW

    override var name = "Jav Guru"
    override var mainUrl = "https://jav.guru"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = true
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val vpnStatus = VPNStatus.MightBeNeeded

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val all = ArrayList<HomePageList>()
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(url).document

        val items = doc.select("main.site-main div.row, div.inside-article").mapNotNull {
            val aa = it.selectFirst("div.imgg a, a") ?: return@mapNotNull null
            val link = fixUrlNull(aa.attr("href")) ?: return@mapNotNull null
            val img = aa.selectFirst("img")
            val name = img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: it.selectFirst("h2, h3, a")?.text()
                ?: return@mapNotNull null
            val image = fixUrlNull(img?.attr("src") ?: img?.attr("data-src"))

            newMovieSearchResponse(name, link, globaltvType) {
                this.posterUrl = image
            }
        }.distinctBy { it.url }

        if (items.isNotEmpty()) {
            all.add(HomePageList("Latest JAV", items, isHorizontalImages = true))
        }

        return newHomePageResponse(all, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encoded = query.trim().replace(" ", "+")

        for (page in 1..3) {
            val url = if (page == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
            val doc = app.get(url).document
            val results = doc.select("main.site-main div.row, div.inside-article").mapNotNull {
                val aa = it.selectFirst("div.imgg a, a") ?: return@mapNotNull null
                val href = fixUrlNull(aa.attr("href")) ?: return@mapNotNull null
                val img = aa.selectFirst("img")
                val title = img?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: it.selectFirst("h2, h3")?.text()
                    ?: return@mapNotNull null
                val image = fixUrlNull(img?.attr("src")?.trim('\'') ?: img?.attr("data-src"))

                newMovieSearchResponse(title, href, globaltvType) {
                    this.posterUrl = image
                }
            }
            val unique = results.filterNot { item -> searchResponse.any { it.url == item.url } }
            if (unique.isEmpty()) break
            searchResponse.addAll(unique)
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val poster = fixUrlNull(
            doc.selectFirst("div.large-screenimg img, meta[property='og:image']")?.attr("src")
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        )
        val title = doc.selectFirst("h1.titl, meta[property='og:title']")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title()
        val descript = doc.selectFirst("div.wp-content p, meta[property='og:description']")?.text()?.trim()

        val infometaList = doc.select("div.infometa div.infoleft ul li")
        val year = infometaList.firstOrNull { it.text().contains("Release Date", ignoreCase = true) }
            ?.text()?.let { Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.value?.toIntOrNull() }

        val tags = doc.select("div.infometa a[href*='/genre/'], div.infometa a[href*='/tag/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = doc.select("div.infometa a[href*='/actress/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = doc.select("div.related-posts div.inside-article, div.row")
            .mapNotNull {
                val aa = it.selectFirst("div.imgg a, a") ?: return@mapNotNull null
                val link = fixUrlNull(aa.attr("href")) ?: return@mapNotNull null
                val name = aa.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: it.selectFirst("h2, h3, a")?.text()
                    ?: return@mapNotNull null
                newMovieSearchResponse(name, link, globaltvType)
            }
            .distinctBy { it.url }
            .filter { it.url != url }

        // Extract embed iframe URLs
        val html = doc.html()
        val iframes = doc.select("div.posts iframe[src], div.wp-content iframe[src], iframe[src]")
            .mapNotNull { fixUrlNull(it.attr("src")) }
            .filter { !it.contains("google") && !it.contains("facebook") }

        val scriptUrls = Regex("""(?:src|file|url)["']?\s*[:=]\s*["'](https?://[^"']+)["']""")
            .findAll(html)
            .map { it.groupValues[1] }
            .filter { it.contains("embed") || it.contains("stream") || it.contains("player") }
            .toList()

        val allUrls = (iframes + scriptUrls).distinct()

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = globaltvType,
            dataUrl = allUrls.toJson(),
        ).apply {
            this.apiName = this@JavGuru.name
            this.posterUrl = poster
            this.year = year
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